package org.kestra.task.gcp.bigquery;

import com.google.cloud.bigquery.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.kestra.core.models.executions.metrics.Counter;
import org.kestra.core.models.executions.metrics.Timer;
import org.kestra.core.models.tasks.RunnableTask;
import org.kestra.core.models.tasks.Task;
import org.kestra.core.runners.RunContext;
import org.slf4j.Logger;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
abstract public class AbstractLoad extends Task implements RunnableTask<AbstractLoad.Output> {
    protected String destinationTable;

    private List<String> clusteringFields;

    private List<JobInfo.SchemaUpdateOption> schemaUpdateOptions;

    private String timePartitioningField;

    private JobInfo.WriteDisposition writeDisposition;

    private Boolean autodetect;

    private JobInfo.CreateDisposition createDisposition;

    private Boolean ignoreUnknownValues;

    private Integer maxBadRecords;

    private Schema schema;

    private Format format;

    private CsvOptions csvOptions;

    private AvroOptions avroOptions;

    @SuppressWarnings("DuplicatedCode")
    protected void setOptions(LoadConfiguration.Builder builder) {
        if (this.clusteringFields != null) {
            builder.setClustering(Clustering.newBuilder().setFields(this.clusteringFields).build());
        }

        if (this.schemaUpdateOptions != null) {
            builder.setSchemaUpdateOptions(this.schemaUpdateOptions);
        }

        if (this.timePartitioningField != null) {
            builder.setTimePartitioning(TimePartitioning.newBuilder(TimePartitioning.Type.DAY)
                .setField(this.timePartitioningField)
                .build()
            );
        }

        if (this.writeDisposition != null) {
            builder.setWriteDisposition(this.writeDisposition);
        }

        if (this.autodetect != null) {
            builder.setAutodetect(autodetect);
        }

        if (this.createDisposition != null) {
            builder.setCreateDisposition(this.createDisposition);
        }

        if (this.ignoreUnknownValues != null) {
            builder.setIgnoreUnknownValues(this.ignoreUnknownValues);
        }

        if (this.maxBadRecords != null) {
            builder.setMaxBadRecords(this.maxBadRecords);
        }

        if (this.schema != null) {
            builder.setSchema(this.schema);
        }

        switch (this.format) {
            case CSV:
                builder.setFormatOptions(this.csvOptions.to());
                break;
            case JSON:
                builder.setFormatOptions(FormatOptions.json());
                break;
            case AVRO:
                builder.setFormatOptions(FormatOptions.avro());

                if (this.avroOptions != null && this.avroOptions.useAvroLogicalTypes != null) {
                    builder.setUseAvroLogicalTypes(this.avroOptions.useAvroLogicalTypes);
                }
                break;
            case PARQUET:
                builder.setFormatOptions(FormatOptions.parquet());
                break;
            case ORC:
                builder.setFormatOptions(FormatOptions.orc());
                break;
        }
    }

    protected Output execute(RunContext runContext, Logger logger, LoadConfiguration configuration, Job job) throws InterruptedException, IOException {
        Connection.handleErrors(job, logger);
        job = job.waitFor();
        Connection.handleErrors(job, logger);

        JobStatistics.LoadStatistics stats = job.getStatistics();
        this.metrics(runContext, stats, job);

        return Output.builder()
            .jobId(job.getJobId().getJob())
            .rows(stats.getOutputRows())
            .destinationTable(configuration.getDestinationTable().getProject() + "." +
                configuration.getDestinationTable().getDataset() + "." +
                configuration.getDestinationTable().getTable())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements org.kestra.core.models.tasks.Output {
        private String jobId;
        private String destinationTable;
        private Long rows;
    }

    private void metrics(RunContext runContext, JobStatistics.LoadStatistics stats, Job job) {
        String[] tags = {
            "destination_table", this.destinationTable,
            "projectId", job.getJobId().getProject(),
            "location", job.getJobId().getLocation(),
        };

        if (stats.getOutputRows() != null) {
            runContext.metric(Counter.of("output.rows", stats.getOutputRows(), tags));
        }

        if (stats.getOutputBytes() != null) {
            runContext.metric(Counter.of("output.bytes", stats.getOutputBytes(), tags));
        }

        if (stats.getBadRecords() != null) {
            runContext.metric(Counter.of("bad.records", stats.getBadRecords(), tags));
        }

        if (stats.getInputBytes() != null) {
            runContext.metric(Counter.of("input.bytes", stats.getInputBytes(), tags));
        }

        if (stats.getInputFiles() != null) {
            runContext.metric(Counter.of("input.files", stats.getInputFiles(), tags));
        }


        runContext.metric(Timer.of("duration", Duration.ofNanos(stats.getEndTime() - stats.getStartTime()), tags));
    }

    public enum Format {
        CSV,
        JSON,
        AVRO,
        PARQUET,
        ORC,
        // GOOGLE_SHEETS,
        // BIGTABLE,
        // DATASTORE_BACKUP,
    }

    @Builder
    @ToString
    @EqualsAndHashCode
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CsvOptions {
        private Boolean allowJaggedRows;
        private Boolean allowQuotedNewLines;
        private String encoding;
        private String fieldDelimiter;
        private String quote;
        private Long skipLeadingRows;

        private com.google.cloud.bigquery.CsvOptions to() {
            com.google.cloud.bigquery.CsvOptions.Builder builder = com.google.cloud.bigquery.CsvOptions.newBuilder();

            if (this.allowJaggedRows != null) {
                builder.setAllowJaggedRows(this.allowJaggedRows);
            }

            if (this.allowQuotedNewLines != null) {
                builder.setAllowQuotedNewLines(this.allowQuotedNewLines);
            }

            if (this.encoding != null) {
                builder.setEncoding(this.encoding);
            }

            if (this.fieldDelimiter != null) {
                builder.setFieldDelimiter(this.fieldDelimiter);
            }

            if (this.quote != null) {
                builder.setQuote(this.quote);
            }

            if (this.skipLeadingRows != null) {
                builder.setSkipLeadingRows(this.skipLeadingRows);
            }

            return builder.build();
        }
    }

    @Builder
    @ToString
    @EqualsAndHashCode
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AvroOptions {
        private Boolean useAvroLogicalTypes;
    }
}
