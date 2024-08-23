/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.stats.prometheus;

import io.netty.buffer.ByteBuf;
import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.pulsar.common.allocator.PulsarByteBufAllocator;
import org.apache.pulsar.common.util.SimpleTextOutputStream;

/**
 * Generate metrics in a text format suitable to be consumed by Prometheus.
 * Format specification can be found at {@link https://prometheus.io/docs/instrumenting/exposition_formats/}
 */
@Slf4j
public class PrometheusMetricsGeneratorUtils {
    private static final Pattern METRIC_LABEL_VALUE_SPECIAL_CHARACTERS = Pattern.compile("[\\\\\"\\n]");

    public static void generate(String cluster, OutputStream out,
                                List<PrometheusRawMetricsProvider> metricsProviders)
            throws IOException {
        log.info("Generating metrics for cluster {}", cluster);
        ByteBuf buf = PulsarByteBufAllocator.DEFAULT.heapBuffer();
        log.info("Allocated ByteBuf with writerIndex={} capacity={}, maxCapacity={}",
                buf.writerIndex(), buf.capacity(), buf.maxCapacity());
        try {
            SimpleTextOutputStream stream = new SimpleTextOutputStream(buf);
            log.info("starting generateSystemMetrics");
            try {
                generateSystemMetrics(stream, cluster);
                log.info("used ByteBuf with writerIndex={} capacity={}, maxCapacity={}",
                        buf.writerIndex(), buf.capacity(), buf.maxCapacity());
            } catch (Throwable e) {
                log.error("Failed to generate system metrics", e);
                throw e;
            }
            if (metricsProviders != null) {
                log.info("starting generate metrics for {} provider(s)", metricsProviders.size());
                for (PrometheusRawMetricsProvider metricsProvider : metricsProviders) {
                    log.info("starting generate for provider {}", metricsProvider.getClass().getName());
                    try {
                        metricsProvider.generate(stream);
                        log.info("used ByteBuf with writerIndex={} capacity={}, maxCapacity={}",
                                buf.writerIndex(), buf.capacity(), buf.maxCapacity());
                    } catch (Throwable e) {
                        log.error("Failed to generate metrics for provider {}",
                                metricsProvider.getClass().getName(), e);
                        throw e;
                    }
                }
            }
            out.write(buf.array(), buf.arrayOffset(), buf.readableBytes());
            log.info("Successfully generated metrics for cluster {}", cluster);
        } catch (Throwable t) {
            log.error("Failed to generate metrics", t);
        } finally {
            buf.release();
        }
    }

    public static void generateSystemMetrics(SimpleTextOutputStream stream, String cluster) {
        Enumeration<Collector.MetricFamilySamples> metricFamilySamples =
                CollectorRegistry.defaultRegistry.metricFamilySamples();
        int count = 0;
        ByteBuf buf = stream.getBuffer();
        while (metricFamilySamples.hasMoreElements()) {
            count++;
            Collector.MetricFamilySamples metricFamily = metricFamilySamples.nextElement();

            log.info("{} Writing metric family {} with type {} ({} samples)",
                    count, metricFamily.name, metricFamily.type,
                    metricFamily.samples.size());

            // Write type of metric
            stream.write("# TYPE ").write(metricFamily.name).write(getTypeNameSuffix(metricFamily.type)).write(' ')
                    .write(getTypeStr(metricFamily.type)).write('\n');

            log.info("used ByteBuf with writerIndex={} capacity={}, maxCapacity={}",
                    buf.writerIndex(), buf.capacity(), buf.maxCapacity());

            for (int i = 0; i < metricFamily.samples.size(); i++) {
                Collector.MetricFamilySamples.Sample sample = metricFamily.samples.get(i);
                log.info("{} Writing sample {} with {} labels", count, sample.name, sample.labelNames.size());

                log.info("used ByteBuf with writerIndex={} capacity={}, maxCapacity={}",
                        buf.writerIndex(), buf.capacity(), buf.maxCapacity());

                stream.write(sample.name);
                stream.write("{");
                if (!sample.labelNames.contains("cluster")) {
                    stream.write("cluster=\"").write(writeEscapedLabelValue(cluster)).write('"');
                    // If label is empty, should not append ','.
                    if (!CollectionUtils.isEmpty(sample.labelNames)){
                        stream.write(",");
                    }
                }
                for (int j = 0; j < sample.labelNames.size(); j++) {
                    String labelValue = writeEscapedLabelValue(sample.labelValues.get(j));
                    if (j > 0) {
                        stream.write(",");
                    }
                    stream.write(sample.labelNames.get(j));
                    stream.write("=\"");
                    stream.write(labelValue);
                    stream.write('"');
                }

                stream.write("} ");
                stream.write(Collector.doubleToGoString(sample.value));
                stream.write('\n');
            }
            log.info("{} Done writing metric family {}", count, metricFamily.name);
        }
    }

    static String getTypeNameSuffix(Collector.Type type) {
        if (type.equals(Collector.Type.INFO)) {
            return "_info";
        }
        return "";
    }

    static String getTypeStr(Collector.Type type) {
        switch (type) {
            case COUNTER:
                return "counter";
            case GAUGE:
            case INFO:
                return "gauge";
            case SUMMARY:
                return "summary";
            case HISTOGRAM:
                return "histogram";
            case UNKNOWN:
            default:
                return "unknown";
        }
    }


    /**
     * Write a label value to the writer, escaping backslashes, double quotes and newlines.
     * See Promethues Exporter io.prometheus.client.exporter.common.TextFormat#writeEscapedLabelValue
     */
    public static String writeEscapedLabelValue(String s) {
        if (s == null) {
            return null;
        }
        if (!labelValueNeedsEscape(s)) {
            return s;
        }
        StringBuilder writer = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                    writer.append("\\\\");
                    break;
                case '\"':
                    writer.append("\\\"");
                    break;
                case '\n':
                    writer.append("\\n");
                    break;
                default:
                    writer.append(c);
            }
        }
        return writer.toString();
    }

    static boolean labelValueNeedsEscape(String s) {
        return METRIC_LABEL_VALUE_SPECIAL_CHARACTERS.matcher(s).find();
    }

}

