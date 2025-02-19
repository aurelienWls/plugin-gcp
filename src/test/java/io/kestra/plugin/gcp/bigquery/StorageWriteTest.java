package io.kestra.plugin.gcp.bigquery;

import com.devskiller.friendly_id.FriendlyId;
import com.google.common.collect.ImmutableMap;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.storages.StorageInterface;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import io.micronaut.context.annotation.Value;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@MicronautTest
class StorageWriteTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private StorageInterface storageInterface;

    @Value("${kestra.tasks.bigquery.project}")
    private String project;

    @Value("${kestra.tasks.bigquery.dataset}")
    private String dataset;

    @Test
    void run() throws Exception {
        String table = this.project  + "." + this.dataset + "." + FriendlyId.createFriendlyId();

        Query create = Query.builder()
            .id(QueryTest.class.getSimpleName())
            .type(Query.class.getName())
            .sql("CREATE TABLE " + table + " AS " + QueryTest.sql())
            .build();
        create.run(TestsUtils.mockRunContext(runContextFactory, create, ImmutableMap.of()));

        HashMap<String, Object> object = new HashMap<>();
        object.put("string", "hello");
        object.put("nullable", null);
        object.put("bool", true);
        object.put("int", 1L);
        object.put("float", 1.25D);
        object.put("date", LocalDate.parse("2008-12-25"));
        object.put("timestamp", ZonedDateTime.parse("2008-12-25T15:30:00.123+01:00"));
        object.put("time", LocalTime.parse("15:30:00.123456"));
        object.put("array", Arrays.asList(1L, 2L, 3L));
        object.put("struct", Map.of("x", 4L, "y", 0L, "z", Arrays.asList(1L, 2L, 3L)));
        object.put("datetime", LocalDateTime.parse("2008-12-25T15:30:00.123"));

        File tempFile = File.createTempFile(this.getClass().getSimpleName().toLowerCase() + "_", ".ion");
        try (FileOutputStream outputStream = new FileOutputStream(tempFile)) {
            FileSerde.write(outputStream, object);
        }

        URI put = storageInterface.put(
            null,
            new URI("/" + IdUtils.create() + ".ion"),
            new FileInputStream(tempFile)
        );

        StorageWrite task = StorageWrite.builder()
            .id("test-unit")
            .type(StorageWrite.class.getName())
            .destinationTable(table)
            .location("EU")
            .from(put.toString())
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, ImmutableMap.of());
        StorageWrite.Output run = task.run(runContext);


//        assertThat(run.getRowsCount(), is(1));
        assertThat(run.getRows(), is(1));
    }
}
