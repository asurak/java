package com.wavefront.agent.formatter;

import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Andrew Kao (andrew@wavefront.com)
 */
public class GraphiteFormatterTest {

  private static final Logger logger = LoggerFactory.getLogger(GraphiteFormatterTest.class);

  @Test
  public void testCollectdGraphiteParsing() {
    String format = "4,3,2"; // Extract the 4th, 3rd, and 2nd segments of the metric as the hostname, in that order
    String delimiter = "_";

    // Test input
    String testString1 = "collectd.com.bigcorp.www02_web.cpu.loadavg.1m 40";
    String testString2 = "collectd.com.bigcorp.www02_web.cpu.loadavg.1m 40 1415233342";
    String testString3 = "collectd.almost.too.short 40 1415233342";
    String testString4 = "collectd.too.short 40 1415233342";

    // Test output
    String expected1 = "collectd.cpu.loadavg.1m 40 source=www02.web.bigcorp.com";
    String expected2 = "collectd.cpu.loadavg.1m 40 1415233342 source=www02.web.bigcorp.com";

    // Test basic functionality with correct input
    GraphiteFormatter formatter = new GraphiteFormatter(format, delimiter);
    String output1 = formatter.apply(testString1);
    Assert.assertEquals(expected1, output1);
    String output2 = formatter.apply(testString2);
    Assert.assertEquals(expected2, output2);

    // Test format length limits
    formatter.apply(testString3); // should not throw exception

    // Do we properly reject metrics that don't work with the given format?
    boolean threwException = false;
    try {
      formatter.apply(testString4); // should be too short for given format
    } catch (IllegalArgumentException e) {
      threwException = true;
    }
    Assert.assertTrue(threwException);

    // Do we properly reject invalid formats?
    String badFormat = "4,2,0"; // nuh-uh; we're doing 1-based indexing
    threwException = false;
    try {
      new GraphiteFormatter(badFormat, delimiter);
    } catch (IllegalArgumentException e) {
      threwException = true;
    }
    Assert.assertTrue(threwException);

    // Benchmark
    long start = System.nanoTime();
    for (int index = 0; index < 1000 * 1000; index++) {
      formatter.apply(testString2);
    }
    long end = System.nanoTime();

    // Report/validate performance
    logger.error(" Time to parse 1M strings: " + (end - start) + " ns for " + formatter.getOps() + " runs");
    long nsPerOps = (end - start) / formatter.getOps();
    logger.error(" ns per op: " + nsPerOps + " and ops/sec " + (1000 * 1000 * 1000 / nsPerOps));
    Assert.assertTrue(formatter.getOps() >= 1000 * 1000);  // make sure we actually ran it 1M times
    Assert.assertTrue(nsPerOps < 10 * 1000); // make sure it was less than 10 μs per run; it's around 1 μs on my machine
  }
}
