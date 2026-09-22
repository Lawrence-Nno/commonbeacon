package com.lawrencenno.commonbeacon.transfer.storage;

import java.io.IOException;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.lawrencenno.commonbeacon.transfer.job.*;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="commonbeacon.transfer.storage.enabled",havingValue="true")
public class ArtifactStoreConfiguration {
    @Bean
    @ConditionalOnProperty(name="commonbeacon.transfer.export.worker.enabled",havingValue="true",matchIfMissing=true)
    com.lawrencenno.commonbeacon.transfer.export.CompanyExportWorker companyExportWorker(TransferJobs jobs,ArtifactStore store,
            com.lawrencenno.commonbeacon.transfer.export.CompanySnapshot snapshot,
            com.lawrencenno.commonbeacon.transfer.export.PersonalSnapshot personal) {
        return new com.lawrencenno.commonbeacon.transfer.export.CompanyExportWorker(jobs,store,snapshot,personal);
    }
    @Bean
    @ConditionalOnProperty(name="commonbeacon.transfer.import.inspection.enabled",havingValue="true",matchIfMissing=true)
    com.lawrencenno.commonbeacon.transfer.inspection.ImportInspector importInspector(TransferJobs jobs,ArtifactStore store) {
        return new com.lawrencenno.commonbeacon.transfer.inspection.ImportInspector(jobs,store);
    }
    @Bean
    @ConditionalOnProperty(name="commonbeacon.transfer.import.dry-run.enabled",havingValue="true",matchIfMissing=true)
    com.lawrencenno.commonbeacon.transfer.inspection.ImportDryRunWorker importDryRunWorker(TransferJobs jobs,ArtifactStore store,ImportDryRuns dryRuns) {
        return new com.lawrencenno.commonbeacon.transfer.inspection.ImportDryRunWorker(jobs,store,dryRuns);
    }
    @Bean TransferWorker transferWorker(TransferJobs jobs,ArtifactStore store) {return new TransferWorker(jobs,store);}
    @Bean ArtifactReconciler artifactReconciler(TransferJobs jobs,ArtifactStore store) {return new ArtifactReconciler(jobs,store);}
    @Bean ArtifactStore artifactStore(@Value("${commonbeacon.transfer.storage.directory}") String directory) throws IOException {
        return new LocalArtifactStore(Path.of(directory),2147483648L,1073741824L);
    }
}
