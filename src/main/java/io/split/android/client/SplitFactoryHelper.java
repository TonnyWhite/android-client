package io.split.android.client;

import android.content.Context;

import androidx.work.WorkManager;

import java.net.URISyntaxException;
import java.util.Map;

import io.split.android.client.api.Key;
import io.split.android.client.network.HttpClient;
import io.split.android.client.network.SplitHttpHeadersBuilder;
import io.split.android.client.service.ServiceFactory;
import io.split.android.client.service.SplitApiFacade;
import io.split.android.client.service.synchronizer.WorkManagerWrapper;
import io.split.android.client.storage.SplitStorageContainer;
import io.split.android.client.storage.db.SplitRoomDatabase;
import io.split.android.client.storage.db.StorageFactory;
import io.split.android.client.utils.NetworkHelper;
import io.split.android.client.utils.Utils;
import io.split.android.engine.metrics.Metrics;

class SplitFactoryHelper {

    String buildDatabaseName(SplitClientConfig splitClientConfig, String apiToken) {
        String databaseName = Utils.convertApiKeyToFolder(apiToken);
        if (databaseName == null) {
            databaseName = splitClientConfig.defaultDataFolder();
        }
        return databaseName;
    }

    Map<String, String> buildHeaders(SplitClientConfig splitClientConfig, String apiToken) {
        SplitHttpHeadersBuilder headersBuilder = new SplitHttpHeadersBuilder();
        headersBuilder.setHostIp(splitClientConfig.ip());
        headersBuilder.setHostname(splitClientConfig.hostname());
        headersBuilder.setClientVersion(SplitClientConfig.splitSdkVersion);
        headersBuilder.setApiToken(apiToken);
        return headersBuilder.build();
    }

    SplitStorageContainer buildStorageContainer(Context context, Key key, String databaseName) {
        SplitRoomDatabase splitRoomDatabase = SplitRoomDatabase.getDatabase(context, databaseName);
        return new SplitStorageContainer(
                StorageFactory.getSplitsStorage(splitRoomDatabase),
                StorageFactory.getMySegmentsStorage(splitRoomDatabase, key.matchingKey()),
                StorageFactory.getPersistenEventsStorage(splitRoomDatabase),
                StorageFactory.getPersistenImpressionsStorage(splitRoomDatabase));
    }

    SplitApiFacade buildApiFacade(SplitClientConfig splitClientConfig,
                                  Key key,
                                  HttpClient httpClient,
                                  Metrics cachedFireAndForgetMetrics) throws URISyntaxException {
        NetworkHelper networkHelper = new NetworkHelper();

        return new SplitApiFacade(
                ServiceFactory.getSplitsFetcher(networkHelper, httpClient,
                        splitClientConfig.endpoint(), cachedFireAndForgetMetrics),
                ServiceFactory.getMySegmentsFetcher(networkHelper, httpClient,
                        splitClientConfig.endpoint(), key.matchingKey(),   cachedFireAndForgetMetrics),
                ServiceFactory.getEventsRecorder(networkHelper, httpClient,
                        splitClientConfig.eventsEndpoint()),
                ServiceFactory.getImpressionsRecorder(networkHelper, httpClient,
                        splitClientConfig.eventsEndpoint()));
    }

    WorkManagerWrapper buildWorkManagerWrapper(Context context, SplitClientConfig splitClientConfig,
                                               String apiKey, String key, String databaseName) {
        return new WorkManagerWrapper(
                WorkManager.getInstance(context), splitClientConfig, apiKey, key, databaseName);

    }
}
