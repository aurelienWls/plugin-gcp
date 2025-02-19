package io.kestra.plugin.gcp.gcs;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import io.kestra.core.models.conditions.ConditionContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.executions.ExecutionTrigger;
import io.kestra.core.models.flows.State;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.gcp.GcpInterface;
import io.kestra.plugin.gcp.gcs.models.Blob;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.stream.Collectors;

import static io.kestra.core.utils.Rethrow.throwFunction;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Wait for files on Google cloud storage",
    description = "This trigger will poll every `interval` a GCS bucket. " +
        "You can search for all files in a bucket or directory in `from` or you can filter the files with a `regExp`." +
        "The detection is atomic, internally we do a list and interact only with files listed.\n" +
        "Once a file is detected, we download the file on internal storage and processed with declared `action` " +
        "in order to move or delete the files from the bucket (to avoid double detection on new poll)"
)
@Plugin(
    examples = {
        @Example(
            title = "Wait for a list of file on a GCS bucket and iterate through the files",
            full = true,
            code = {
                "id: gcs-listen",
                "namespace: io.kestra.tests",
                "",
                "tasks:",
                "  - id: each",
                "    type: io.kestra.core.tasks.flows.EachSequential",
                "    tasks:",
                "      - id: return",
                "        type: io.kestra.core.tasks.debugs.Return",
                "        format: \"{{taskrun.value}}\"",
                "    value: \"{{ trigger.blobs | jq('.[].uri') }}\"",
                "",
                "triggers:",
                "  - id: watch",
                "    type: io.kestra.plugin.gcp.gcs.Trigger",
                "    interval: \"PT5M\"",
                "    from: gs://my-bucket/kestra/listen/",
                "    action: MOVE",
                "    moveDirectory: gs://my-bucket/kestra/archive/",
            }
        ),
        @Example(
            title = "Wait for a list of files on a GCS bucket and iterate through the files. Delete files manually after processing to prevent infinite triggering.",
            full = true,
            code = {
                "id: s3-listen",
                "namespace: io.kestra.tests",
                "",
                "tasks:",
                "  - id: each",
                "    type: io.kestra.core.tasks.flows.EachSequential",
                "    tasks:",
                "      - id: return",
                "        type: io.kestra.core.tasks.debugs.Return",
                "        format: \"{{ taskrun.value }}\"",
                "      - id: delete",
                "        type: io.kestra.plugin.gcp.gcs.Delete",
                "        uri: \"{{ taskrun.value }}\"",
                "    value: \"{{ trigger.blobs | jq('.[].uri') }}\"",
                "",
                "triggers:",
                "  - id: watch",
                "    type: io.kestra.plugin.gcp.gcs.Trigger",
                "    interval: \"PT5M\"",
                "    from: gs://my-bucket/kestra/listen/",
                "    action: NONE",
            }
        )
    }
)
public class Trigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<Downloads.Output>, GcpInterface, ListInterface, ActionInterface{
    @Builder.Default
    private final Duration interval = Duration.ofSeconds(60);

    protected String projectId;
    protected String serviceAccount;

    @Builder.Default
    protected java.util.List<String> scopes = Collections.singletonList("https://www.googleapis.com/auth/cloud-platform");

    private String from;

    private ActionInterface.Action action;

    private String moveDirectory;

    @Builder.Default
    private final List.ListingType listingType = ListInterface.ListingType.DIRECTORY;

    @PluginProperty(dynamic = true)
    private String regExp;

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        RunContext runContext = conditionContext.getRunContext();
        List task = List.builder()
            .id(this.id)
            .type(List.class.getName())
            .projectId(this.projectId)
            .serviceAccount(this.serviceAccount)
            .scopes(this.scopes)
            .from(this.from)
            .filter(ListInterface.Filter.FILES)
            .listingType(this.listingType)
            .regExp(this.regExp)
            .build();
        List.Output run = task.run(runContext);

        if (run.getBlobs().isEmpty()) {
            return Optional.empty();
        }

        Storage connection = task.connection(runContext);

        java.util.List<Blob> list = run
            .getBlobs()
            .stream()
            .map(throwFunction(blob -> {
                URI uri = runContext.putTempFile(
                    Download.download(runContext, connection, BlobId.of(blob.getBucket(), blob.getName())),
                    runContext.getTriggerExecutionId(),
                    this
                );

                return blob.withUri(uri);
            }))
            .collect(Collectors.toList());

        Downloads.performAction(
            run.getBlobs(),
            this.action,
            this.moveDirectory,
            runContext,
            this.projectId,
            this.serviceAccount,
            this.scopes
        );

        ExecutionTrigger executionTrigger = ExecutionTrigger.of(
            this,
            Downloads.Output.builder().blobs(list).build()
        );

        Execution execution = Execution.builder()
            .id(runContext.getTriggerExecutionId())
            .namespace(context.getNamespace())
            .flowId(context.getFlowId())
            .flowRevision(context.getFlowRevision())
            .state(new State())
            .trigger(executionTrigger)
            .build();

        return Optional.of(execution);
    }
}
