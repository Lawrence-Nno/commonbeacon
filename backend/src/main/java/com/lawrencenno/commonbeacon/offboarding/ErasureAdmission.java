package com.lawrencenno.commonbeacon.offboarding;

import com.lawrencenno.commonbeacon.shared.ApiFailure;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Drains HTTP responses in the supported single-backend deployment before confirmation.
 * The persisted job and PostgreSQL gate protect subsequent requests and database writers. */
@Component
public class ErasureAdmission {
    private final ReentrantReadWriteLock lock=new ReentrantReadWriteLock(true);
    public boolean enter(){return lock.readLock().tryLock();}
    public void leave(){lock.readLock().unlock();}
    public <T>T confirm(Supplier<T> action) {
        boolean acquired=false;
        try {
            acquired=lock.writeLock().tryLock(2,TimeUnit.SECONDS);
            if(!acquired)throw new ApiFailure(409,"ERASURE_BUSY","Wait for active requests to finish, then refresh the preview.");
            return action.get();
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();throw new ApiFailure(409,"ERASURE_BUSY","Refresh the erasure preview and try again.");
        } finally {if(acquired)lock.writeLock().unlock();}
    }
}
