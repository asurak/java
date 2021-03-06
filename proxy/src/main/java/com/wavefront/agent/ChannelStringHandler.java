package com.wavefront.agent;

import com.google.common.base.Function;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;

import com.wavefront.ingester.Decoder;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.MetricName;

import org.apache.commons.lang.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import sunnylabs.report.ReportPoint;

/**
 * Parses points from a channel using the given decoder and send it off to the AgentAPI interface.
 *
 * @author Clement Pang (clement@wavefront.com).
 */
@ChannelHandler.Sharable
public class ChannelStringHandler extends SimpleChannelInboundHandler<String> {

  private final Decoder decoder;
  private final String prefix;
  /**
   * Transformer to transform each line.
   */
  @Nullable
  private final Function<String, String> transformer;
  private final PointHandler pointHandler;

  @Nullable
  private final Pattern pointLineWhiteList;
  @Nullable
  private final Pattern pointLineBlackList;

  private final Counter regexRejects;

  private int blockedPointsPerBatch;

  public ChannelStringHandler(Decoder decoder,
                              final int port,
                              final String prefix,
                              final String validationLevel,
                              final int blockedPointsPerBatch,
                              final PostPushDataTimedTask[] postPushDataTimedTasks,
                              @Nullable final Function<String, String> transformer,
                              @Nullable final String pointLineWhiteListRegex,
                              @Nullable final String pointLineBlackListRegex) {
    this.decoder = decoder;
    this.pointHandler = new PointHandler(port, validationLevel, blockedPointsPerBatch, postPushDataTimedTasks);

    this.prefix = prefix;
    this.blockedPointsPerBatch = blockedPointsPerBatch;
    this.transformer = transformer;

    this.pointLineWhiteList = StringUtils.isBlank(pointLineWhiteListRegex) ?
        null : Pattern.compile(pointLineWhiteListRegex);

    this.pointLineBlackList = StringUtils.isBlank(pointLineBlackListRegex) ?
        null : Pattern.compile(pointLineBlackListRegex);

    this.regexRejects = Metrics.newCounter(
        new MetricName("validationRegex." + String.valueOf(port), "", "points-rejected"));
  }

  public static final String PUSH_DATA_DELIMETER = "\n";

  public static List<String> unjoinPushData(String pushData) {
    return Arrays.asList(StringUtils.split(pushData, PUSH_DATA_DELIMETER));
  }

  public static String joinPushData(List<String> pushData) {
    return StringUtils.join(pushData, PUSH_DATA_DELIMETER);
  }

  protected boolean passesWhiteAndBlackLists(String pointLine) {
    if (pointLineWhiteList != null && !pointLineWhiteList.matcher(pointLine).matches() ||
        pointLineBlackList != null && pointLineBlackList.matcher(pointLine).matches()) {
      regexRejects.inc();
      return false;
    }
    return true;
  }

  /**
   * Verify the given point is valid to send to WF.
   * @param point The point to verify.
   * @return A human readable error message regarding the point, or empty string if the point is OK.
   */
  private String verifyPoint(ReportPoint point) {
    StringBuilder sb = new StringBuilder();
    if (point.getHost().length() >= 1024) {
      sb.append(" Host too long: ").append(point.getHost()).append(".");
    }
    if (point.getMetric().length() >= 1024) {
      sb.append(" Metric too long: ").append(point.getMetric()).append(".");
    }
    // Each tag of the form "k=v" must be < 256
    for (Map.Entry<String, String> tag : point.getAnnotations().entrySet()) {
      if (tag.getKey().length() + tag.getValue().length() >= 255) {
        sb.append(" Tag too long: ").append(tag.getKey()).append("=").append(tag.getValue())
            .append(".");
      }
    }
    if (sb.length() > 0) {
      sb.insert(0, "WF-301: Point is malformed.");
      return sb.toString();
    }
    return "";
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, String msg) throws Exception {
    // ignore empty lines.
    if (msg == null || msg.trim().length() == 0) return;
    if (transformer != null) {
      msg = transformer.apply(msg);
    }
    String pointLine = msg.trim();

    // apply white/black lists after formatting, but before prefixing
    if (!passesWhiteAndBlackLists(pointLine)) {
      handleBlockedPoint(pointLine);
      return;
    }
    if (prefix != null) {
      pointLine = prefix + "." + msg;
    }
    List<ReportPoint> points = Lists.newArrayListWithExpectedSize(1);
    try {
      decoder.decodeReportPoints(pointLine, points, "dummy");
    } catch (Exception e) {
      final Throwable rootCause = Throwables.getRootCause(e);
      if (rootCause == null || rootCause.getMessage() == null) {
        final String message = "WF-300 Cannot parse: \"" + pointLine +
            "\", reason: \"" + e.getMessage() + "\"";
        handleBlockedPoint(message);
      } else {
        final String message = "WF-300 Cannot parse: \"" + pointLine +
            "\", reason: \"" + e.getMessage() +
            "\", root cause: \"" + rootCause.getMessage() + "\"";
        handleBlockedPoint(message);
      }
    }
    if (!points.isEmpty()) {
      ReportPoint point = points.get(0);
      String errorMessage = verifyPoint(point);
      if (!errorMessage.equals("")) {
        handleBlockedPoint(errorMessage);
      } else {
        pointHandler.reportPoint(point, pointLine);
      }
    }
  }

  private void handleBlockedPoint(String pointLine) {
    final PostPushDataTimedTask randomPostTask = pointHandler.getRandomPostTask();
    if (randomPostTask.getBlockedSampleSize() < this.blockedPointsPerBatch) {
      randomPostTask.addBlockedSample(pointLine);
    }
    randomPostTask.incrementBlockedPoints();
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    // ignore.
  }
}
