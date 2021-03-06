package io.split.android.client.service.splits;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

import io.split.android.client.dtos.SplitChange;
import io.split.android.client.service.executor.SplitTaskExecutionInfo;
import io.split.android.client.service.executor.SplitTaskType;
import io.split.android.client.service.http.HttpFetcher;
import io.split.android.client.service.http.HttpFetcherException;
import io.split.android.client.service.sseclient.ReconnectBackoffCounter;
import io.split.android.client.storage.splits.SplitsStorage;
import io.split.android.client.utils.Logger;
import io.split.android.client.service.executor.SplitTask;

import static com.google.common.base.Preconditions.checkNotNull;

public class SplitsSyncTask implements SplitTask {

    static final String SINCE_PARAM = "since";
    private final HttpFetcher<SplitChange> mSplitFetcher;
    private final SplitsStorage mSplitsStorage;
    private final SplitChangeProcessor mSplitChangeProcessor;
    private boolean mRetryOnFail;

    private static final int RETRY_BASE = 1;

    public SplitsSyncTask(HttpFetcher<SplitChange> splitFetcher,
                          SplitsStorage splitsStorage,
                          SplitChangeProcessor splitChangeProcessor,
                          boolean retryOnFail) {
        mSplitFetcher = checkNotNull(splitFetcher);
        mSplitsStorage = checkNotNull(splitsStorage);
        mSplitChangeProcessor = checkNotNull(splitChangeProcessor);
        mRetryOnFail = retryOnFail;
    }

    @Override
    @NonNull
    public SplitTaskExecutionInfo execute() {
        // TODO: Add some reusable logic for tasks retrying on error.
        ReconnectBackoffCounter backoffCounter = new ReconnectBackoffCounter(RETRY_BASE);
        boolean success = false;
        Map<String, Object> params = new HashMap<>();
        params.put(SINCE_PARAM, mSplitsStorage.getTill());
        while(!success) {
            try {
                SplitChange splitChange = mSplitFetcher.execute(params);
                mSplitsStorage.update(mSplitChangeProcessor.process(splitChange));
                success = true;
            } catch (HttpFetcherException e) {
                logError("Newtwork error while fetching splits" + e.getLocalizedMessage());
                if(mRetryOnFail) {
                    try {
                        logError("Retrying...");
                        Thread.sleep(backoffCounter.getNextRetryTime());
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return SplitTaskExecutionInfo.error(SplitTaskType.SPLITS_SYNC);
                    }
                } else {
                    return SplitTaskExecutionInfo.error(SplitTaskType.SPLITS_SYNC);
                }
            } catch (Exception e) {
                logError("Unexpected while fetching splits" + e.getLocalizedMessage());
                return SplitTaskExecutionInfo.error(SplitTaskType.SPLITS_SYNC);
            }
        }
        Logger.d("Features have been updated");
        return SplitTaskExecutionInfo.success(SplitTaskType.SPLITS_SYNC);
    }

    private void logError(String message) {
        Logger.e("Error while executing splits sync task: " + message);
    }
}
