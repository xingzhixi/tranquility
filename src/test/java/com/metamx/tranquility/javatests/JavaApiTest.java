/*
 * Tranquility.
 * Copyright (C) 2013, 2014  Metamarkets Group Inc.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package com.metamx.tranquility.javatests;

import backtype.storm.task.IMetricsContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.metamx.common.Granularity;
import com.metamx.tranquility.beam.Beam;
import com.metamx.tranquility.beam.ClusteredBeamTuning;
import com.metamx.tranquility.druid.DruidBeams;
import com.metamx.tranquility.druid.DruidDimensions;
import com.metamx.tranquility.druid.DruidLocation;
import com.metamx.tranquility.druid.DruidRollup;
import com.metamx.tranquility.druid.SchemalessDruidDimensions;
import com.metamx.tranquility.druid.SpecificDruidDimensions;
import com.metamx.tranquility.storm.BeamBolt;
import com.metamx.tranquility.storm.BeamFactory;
import com.metamx.tranquility.typeclass.JavaObjectWriter;
import com.metamx.tranquility.typeclass.Timestamper;
import com.twitter.finagle.Service;
import io.druid.granularity.QueryGranularity;
import io.druid.query.aggregation.AggregatorFactory;
import io.druid.query.aggregation.CountAggregatorFactory;
import junit.framework.Assert;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.TestingCluster;
import org.joda.time.DateTime;
import org.joda.time.Period;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class JavaApiTest
{
  private static final List<String> dimensions = ImmutableList.of("column");
  private static final List<AggregatorFactory> aggregators = ImmutableList.<AggregatorFactory>of(
      new CountAggregatorFactory(
          "cnt"
      )
  );

  public static class MyBeamFactory implements BeamFactory<Map<String, Object>>
  {
    @Override
    public Beam<Map<String, Object>> makeBeam(Map<?, ?> conf, IMetricsContext metrics)
    {
      try (
          final TestingCluster cluster = new TestingCluster(1);
          final CuratorFramework curator = CuratorFrameworkFactory.builder()
                                                                  .connectString(cluster.getConnectString())
                                                                  .retryPolicy(
                                                                      new RetryOneTime(1000)
                                                                  )
                                                                  .build()
      ) {
        cluster.start();
        curator.start();

        final String dataSource = "hey";

        final DruidBeams.Builder<Map<String, Object>> builder = DruidBeams
            .builder(
                new Timestamper<Map<String, Object>>()
                {
                  @Override
                  public DateTime timestamp(Map<String, Object> theMap)
                  {
                    return new DateTime(theMap.get("timestamp"));
                  }
                }
            )
            .curator(curator)
            .discoveryPath("/test/discovery")
            .location(
                DruidLocation.create(
                    "druid:local:indexer",
                    "druid:local:firehose:%s",
                    dataSource
                )
            )
            .rollup(DruidRollup.create(dimensions, aggregators, QueryGranularity.MINUTE))
            .tuning(
                ClusteredBeamTuning.builder()
                                   .segmentGranularity(Granularity.HOUR)
                                   .windowPeriod(new Period("PT10M"))
                                   .build()
            )
            .objectWriter(
                new JavaObjectWriter<Map<String, Object>>()
                {
                  final ObjectMapper objectMapper = new ObjectMapper();

                  @Override
                  public byte[] asBytes(Map<String, Object> obj)
                  {
                    try {
                      return objectMapper.writeValueAsBytes(obj);
                    }
                    catch (JsonProcessingException e) {
                      throw Throwables.propagate(e);
                    }
                  }

                  @Override
                  public byte[] batchAsBytes(Iterator<Map<String, Object>> objects)
                  {
                    try {
                      return objectMapper.writeValueAsBytes(ImmutableList.of(objects));
                    }
                    catch (JsonProcessingException e) {
                      throw Throwables.propagate(e);
                    }
                  }
                }
            );

        final Service<List<Map<String, Object>>, Integer> service = builder.buildJavaService();
        Assert.assertNotNull(service);
        final Beam<Map<String, Object>> beam = builder.buildBeam();
        Assert.assertNotNull(beam);
        return beam;
      }
      catch (Exception e) {
        throw Throwables.propagate(e);
      }
    }
  }

  @Test
  public void testSpecificDimensionsRollupConfiguration() throws Exception
  {
    final DruidRollup rollup = DruidRollup.create(
        DruidDimensions.specific(dimensions),
        aggregators,
        QueryGranularity.MINUTE
    );
    Assert.assertTrue(rollup.dimensions() instanceof SpecificDruidDimensions);
    Assert.assertEquals("column", ((SpecificDruidDimensions) rollup.dimensions()).dimensions().apply(0));
  }

  @Test
  public void testSchemalessDimensionsRollupConfiguration() throws Exception
  {
    final DruidRollup rollup = DruidRollup.create(
        DruidDimensions.schemaless(),
        aggregators,
        QueryGranularity.MINUTE
    );
    Assert.assertTrue(rollup.dimensions() instanceof SchemalessDruidDimensions);
    Assert.assertEquals(0, ((SchemalessDruidDimensions) rollup.dimensions()).dimensionExclusions().size());
  }

  @Test
  public void testSchemalessDimensionsWithExclusionsRollupConfiguration() throws Exception
  {
    final DruidRollup rollup = DruidRollup.create(
        DruidDimensions.schemalessWithExclusions(dimensions),
        aggregators,
        QueryGranularity.MINUTE
    );
    Assert.assertTrue(rollup.dimensions() instanceof SchemalessDruidDimensions);
    Assert.assertEquals("column", ((SchemalessDruidDimensions) rollup.dimensions()).dimensionExclusions().apply(0));
  }

  @Test
  public void testDruidBeamBoltConstruction() throws Exception
  {
    final BeamBolt<Map<String, Object>> beamBolt = new BeamBolt<>(new MyBeamFactory());

    // Ensure serializability
    final ObjectOutputStream objectOutputStream = new ObjectOutputStream(new ByteArrayOutputStream());
    objectOutputStream.writeObject(beamBolt);
    Assert.assertTrue(true);
  }
}
