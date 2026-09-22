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
            com.lawrencenno.commonbeacon.transfer.export.CompanySnapshot snapshot) {
        return new com.lawrencenno.commonbeacon.transfer.export.CompanyExportWorker(jobs,store,snapshot);
    }
    @Bean TransferWorker transferWorker(TransferJobs jobs,ArtifactStore store) {return new TransferWorker(jobs,store);}
    @Bean ArtifactReconciler artifactReconciler(TransferJobs jobs,ArtifactStore store) {return new ArtifactReconciler(jobs,store);}
    @Bean ArtifactStore artifactStore(@Value("${commonbeacon.transfer.storage.directory}") String directory) throws IOException {
        return new LocalArtifactStore(Path.of(directory),2147483648L,1073741824L);
    }
}
