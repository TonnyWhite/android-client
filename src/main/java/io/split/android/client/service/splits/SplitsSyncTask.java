package io.split.android.client.service.splits;

import java.util.HashMap;
import java.util.Map;

import io.split.android.client.dtos.SplitChange;
import io.split.android.client.service.HttpFetcher;
import io.split.android.client.service.splits.SplitChangeProcessor;
import io.split.android.client.storage.splits.SplitsStorage;
import io.split.android.client.utils.Logger;
import io.split.android.client.service.executor.SplitTask;

import static com.google.common.base.Preconditions.checkNotNull;

public class SplitsSyncTask implements SplitTask {

    static final String SINCE_PARAM = "since";
    private final HttpFetcher<SplitChange> mSplitFetcher;
    private final SplitsStorage mSplitsStorage;
    private final SplitChangeProcessor mSplitChangeProcessor;


    public SplitsSyncTask(HttpFetcher<SplitChange> splitFetcher,
                          SplitsStorage splitsStorage,
                          SplitChangeProcessor splitChangeProcessor) {
        checkNotNull(splitFetcher);
        checkNotNull(splitsStorage);
        checkNotNull(splitChangeProcessor);

        mSplitFetcher = splitFetcher;
        mSplitsStorage = splitsStorage;
        mSplitChangeProcessor = splitChangeProcessor;
    }

    @Override
    public void execute() {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put(SINCE_PARAM, -1);
            SplitChange splitChange = mSplitFetcher.execute(params);
            mSplitsStorage.update(mSplitChangeProcessor.process(splitChange));
        } catch (IllegalStateException e) {
            logError(e.getLocalizedMessage());
        } catch (Exception e) {
            logError("unexpected " + e.getLocalizedMessage());
        }
    }

    private void logError(String message) {
        Logger.e("Error while executing splits sync task: " + message);
    }
}