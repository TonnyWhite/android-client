package io.split.android.client;

import android.content.Context;

import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLContexts;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLContext;

import io.split.android.client.api.Key;
import io.split.android.client.cache.ISplitCache;
import io.split.android.client.cache.ISplitChangeCache;
import io.split.android.client.cache.SplitCache;
import io.split.android.client.cache.SplitChangeCache;
import io.split.android.client.events.SplitEventsManager;
import io.split.android.client.factory.FactoryMonitor;
import io.split.android.client.factory.FactoryMonitorImpl;
import io.split.android.client.impressions.ImpressionListener;
import io.split.android.client.impressions.ImpressionsManager;
import io.split.android.client.impressions.ImpressionsStorageManager;
import io.split.android.client.impressions.ImpressionsStorageManagerConfig;
import io.split.android.client.interceptors.AddSplitHeadersFilter;
import io.split.android.client.interceptors.GzipDecoderResponseInterceptor;
import io.split.android.client.interceptors.GzipEncoderRequestInterceptor;
import io.split.android.client.metrics.CachedMetrics;
import io.split.android.client.metrics.FireAndForgetMetrics;
import io.split.android.client.metrics.HttpMetrics;
import io.split.android.client.network.HttpClient;
import io.split.android.client.network.HttpClientImpl;
import io.split.android.client.network.SplitHttpHeadersBuilder;
import io.split.android.client.storage.FileStorage;
import io.split.android.client.storage.IStorage;
import io.split.android.client.track.TrackClientConfig;
import io.split.android.client.track.TrackStorageManager;
import io.split.android.client.utils.Logger;
import io.split.android.client.utils.Utils;
import io.split.android.client.validators.ApiKeyValidator;
import io.split.android.client.validators.ApiKeyValidatorImpl;
import io.split.android.client.validators.ValidationConfig;
import io.split.android.client.validators.ValidationErrorInfo;
import io.split.android.client.validators.ValidationMessageLogger;
import io.split.android.client.validators.ValidationMessageLoggerImpl;
import io.split.android.engine.SDKReadinessGates;
import io.split.android.engine.experiments.RefreshableSplitFetcherProvider;
import io.split.android.engine.experiments.SplitChangeFetcher;
import io.split.android.engine.experiments.SplitParser;
import io.split.android.engine.segments.MySegmentsFetcher;
import io.split.android.engine.segments.RefreshableMySegmentsFetcherProviderImpl;

public class SplitFactoryImpl implements SplitFactory {

    private static Random RANDOM = new Random();

    private final SplitClient _client;
    private final SplitManager _manager;
    private final Runnable destroyer;
    private final Runnable flusher;
    private boolean isTerminated = false;
    private final String _apiKey;


    private SplitEventsManager _eventsManager;
    private SDKReadinessGates gates;

    private TrackClient _trackClient;
    FactoryMonitor _factoryMonitor = FactoryMonitorImpl.getSharedInstance();

    public SplitFactoryImpl(String apiToken, Key key, SplitClientConfig config, Context context) throws IOException, InterruptedException, TimeoutException, URISyntaxException {

        ValidationConfig.getInstance().setMaximumKeyLength(config.maximumKeyLength());
        ValidationConfig.getInstance().setTrackEventNamePattern(config.trackEventNamePattern());
        ApiKeyValidator apiKeyValidator = new ApiKeyValidatorImpl();
        ValidationMessageLogger validationLogger = new ValidationMessageLoggerImpl();

        ValidationErrorInfo errorInfo = apiKeyValidator.validate(apiToken);
        String validationTag = "factory instantiation";
        if(errorInfo != null) {
            validationLogger.log(errorInfo, validationTag);
        }

        int factoryCount = _factoryMonitor.count(apiToken);
        if (factoryCount > 0) {
            validationLogger.w( "You already have " + factoryCount + (factoryCount == 1 ? " factory" : " factories") + "with this API Key. We recommend keeping only " +
                    "one instance of the factory at all times (Singleton pattern) and reusing it throughout your application.", validationTag);
        } else if (_factoryMonitor.count() > 0) {
            validationLogger.w("You already have an instance of the Split factory. Make sure you definitely want this additional instance. We recommend " +
                            "keeping only one instance of the factory at all times (Singleton pattern) and reusing it throughout your application.", validationTag);
        }
        _factoryMonitor.add(apiToken);
        _apiKey = apiToken;


        SSLContext sslContext = null;
        try {
            sslContext = SSLContexts.custom()
                    .useTLS()
                    .build();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new RuntimeException("Unable to create support for secure connection.");
        }

        SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactory(
                sslContext,
                new String[]{"TLSv1.1", "TLSv1.2"},
                null,
                SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);

        Registry<ConnectionSocketFactory> registry = RegistryBuilder.<ConnectionSocketFactory>create()
                .register("http", PlainConnectionSocketFactory.getSocketFactory())
                .register("https", sslsf)
                .build();

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(config.connectionTimeout())
                .setSocketTimeout(config.readTimeout())
                .build();

        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager(registry);
        cm.setMaxTotal(20);
        cm.setDefaultMaxPerRoute(20);

        HttpClientBuilder httpClientbuilder = HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(requestConfig)
                .setSSLSocketFactory(sslsf)
                .addInterceptorLast(AddSplitHeadersFilter.instance(apiToken, config.hostname(), config.ip()))
                .addInterceptorLast(new GzipEncoderRequestInterceptor())
                .addInterceptorLast(new GzipDecoderResponseInterceptor());




        // Set up proxy is it exists
        if (config.proxy() != null) {
            Logger.i("Initializing Split SDK with proxy settings");
            DefaultProxyRoutePlanner routePlanner = new DefaultProxyRoutePlanner(config.proxy());
            httpClientbuilder.setRoutePlanner(routePlanner);

            if (config.proxyUsername() != null && config.proxyPassword() != null) {
                Logger.i("Proxy setup using credentials");
                CredentialsProvider credsProvider = new BasicCredentialsProvider();
                AuthScope siteScope = new AuthScope(config.proxy().getHostName(), config.proxy().getPort());
                Credentials siteCreds = new UsernamePasswordCredentials(config.proxyUsername(), config.proxyPassword());
                credsProvider.setCredentials(siteScope, siteCreds);

                httpClientbuilder.setDefaultCredentialsProvider(credsProvider);
            }
        }

        SplitHttpHeadersBuilder headersBuilder  = new SplitHttpHeadersBuilder();
        headersBuilder.setHostIp(config.ip());
        headersBuilder.setHostname(config.hostname());
        headersBuilder.setClientVersion(SplitClientConfig.splitSdkVersion);
        headersBuilder.setApiToken(apiToken);

        final HttpClient httpClient = new HttpClientImpl();
        httpClient.addHeaders(headersBuilder.build());

        final CloseableHttpClient httpclient = httpClientbuilder.build();

        URI rootTarget = URI.create(config.endpoint());
        URI eventsRootTarget = URI.create(config.eventsEndpoint());

        // TODO: 11/23/17  Add MetricsCache
        // Metrics
        HttpMetrics httpMetrics = HttpMetrics.create(httpclient, eventsRootTarget);
        final FireAndForgetMetrics uncachedFireAndForget = FireAndForgetMetrics.instance(httpMetrics, 2, 1000);

        _eventsManager = new SplitEventsManager(config);
        gates = new SDKReadinessGates();

        String dataFolderName = Utils.convertApiKeyToFolder(apiToken);
        if(dataFolderName == null) {
            dataFolderName = config.defaultDataFolder();
        }

        // Segments
        IStorage mySegmentsStorage = new FileStorage(context.getCacheDir(), dataFolderName);
        MySegmentsFetcher mySegmentsFetcher = HttpMySegmentsFetcher.create(httpclient, rootTarget, mySegmentsStorage);
        final RefreshableMySegmentsFetcherProviderImpl segmentFetcher = new RefreshableMySegmentsFetcherProviderImpl(mySegmentsFetcher, findPollingPeriod(RANDOM, config.segmentsRefreshRate()), key.matchingKey(), _eventsManager);

        SplitParser splitParser = new SplitParser(segmentFetcher);

        // Feature Changes
        IStorage fileStorage = new FileStorage(context.getCacheDir(), dataFolderName);
        ISplitCache splitCache = new SplitCache(fileStorage);
        ISplitChangeCache splitChangeCache = new SplitChangeCache(splitCache);

        SplitChangeFetcher splitChangeFetcher = HttpSplitChangeFetcher.create(httpClient, rootTarget, uncachedFireAndForget, splitChangeCache);
        final RefreshableSplitFetcherProvider splitFetcherProvider = new RefreshableSplitFetcherProvider(splitChangeFetcher, splitParser, findPollingPeriod(RANDOM, config.featuresRefreshRate()), _eventsManager);

        // Impressions
        ImpressionsStorageManagerConfig impressionsStorageManagerConfig = new ImpressionsStorageManagerConfig();
        impressionsStorageManagerConfig.setImpressionsMaxSentAttempts(config.impressionsMaxSentAttempts());
        impressionsStorageManagerConfig.setImpressionsChunkOudatedTime(config.impressionsChunkOutdatedTime());
        IStorage impressionsStorage = new FileStorage(context.getCacheDir(), dataFolderName);
        final ImpressionsStorageManager impressionsStorageManager = new ImpressionsStorageManager(impressionsStorage, impressionsStorageManagerConfig);
        final ImpressionsManager splitImpressionListener = ImpressionsManager.instance(httpClient, config, impressionsStorageManager);
        final ImpressionListener impressionListener;

        if (config.impressionListener() != null) {
            List<ImpressionListener> impressionListeners = new ArrayList<ImpressionListener>();
            impressionListeners.add(splitImpressionListener);
            impressionListeners.add(config.impressionListener());
            impressionListener = new ImpressionListener.FederatedImpressionListener(impressionListeners);
        } else {
            impressionListener = splitImpressionListener;
        }

        CachedMetrics cachedMetrics = new CachedMetrics(httpMetrics, TimeUnit.SECONDS.toMillis(config.metricsRefreshRate()));
        final FireAndForgetMetrics cachedFireAndForgetMetrics = FireAndForgetMetrics.instance(cachedMetrics, 2, 1000);


        TrackClientConfig trackConfig = new TrackClientConfig();
        trackConfig.setFlushIntervalMillis(config.eventFlushInterval());
        trackConfig.setMaxEventsPerPost(config.eventsPerPush());
        trackConfig.setMaxQueueSize(config.eventsQueueSize());
        trackConfig.setWaitBeforeShutdown(config.waitBeforeShutdown());
        trackConfig.setMaxSentAttempts(config.eventsMaxSentAttempts());
        trackConfig.setMaxQueueSizeInBytes(config.maxQueueSizeInBytes());
        IStorage eventsStorage = new FileStorage(context.getCacheDir(), dataFolderName);
        TrackStorageManager trackStorageManager = new TrackStorageManager(eventsStorage);
        _trackClient = TrackClientImpl.create(trackConfig, httpclient, eventsRootTarget, trackStorageManager, splitCache);


        destroyer = new Runnable() {
            public void run() {
                Logger.w("Shutdown called for split");
                try {
                    _factoryMonitor.remove(_apiKey);
                    _trackClient.close();
                    Logger.i("Successful shutdown of Track client");
                    segmentFetcher.close();
                    Logger.i("Successful shutdown of segment fetchers");
                    splitFetcherProvider.close();
                    Logger.i("Successful shutdown of splits");
                    uncachedFireAndForget.close();
                    Logger.i("Successful shutdown of metrics 1");
                    cachedFireAndForgetMetrics.close();
                    Logger.i("Successful shutdown of metrics 2");
                    impressionListener.close();
                    Logger.i("Successful shutdown of ImpressionListener");
                    httpclient.close();
                    Logger.i("Successful shutdown of httpclient");
                    _manager.destroy();
                    Logger.i("Successful shutdown of manager");

                } catch (IOException e) {
                    Logger.e(e, "We could not shutdown split");
                } finally {
                    isTerminated = true;
                }
            }
        };

        flusher = new Runnable() {
            @Override
            public void run() {
                Logger.w("Flush called for split");
                try {
                    _trackClient.flush();
                    Logger.i("Successful flush of track client");
                    splitImpressionListener.flushImpressions();
                    Logger.i("Successful flush of impressions");
                } catch (Exception e) {
                    Logger.e(e, "We could not flush split");
                }
            }
        };

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                // Using the full path to avoid conflicting with Thread.destroy()
                SplitFactoryImpl.this.destroy();
            }
        });


        _client = new SplitClientImpl(this, key, splitFetcherProvider.getFetcher(),
                impressionListener, cachedFireAndForgetMetrics, config, _eventsManager, _trackClient, splitCache);
        _manager = new SplitManagerImpl(splitFetcherProvider.getFetcher());

        _eventsManager.getExecutorResources().setSplitClient(_client);

        boolean dataReady = true;
        Logger.i("Android SDK initialized!");
    }

    private static int findPollingPeriod(Random rand, int max) {
        int min = max / 2;
        return rand.nextInt((max - min) + 1) + min;
    }

    public SplitClient client() {
        return _client;
    }

    public SplitManager manager() {
        return _manager;
    }

    public void destroy() {
        synchronized (SplitFactoryImpl.class) {
            if (!isTerminated) {
                new Thread(destroyer).start();
            }
        }
    }

    @Override
    public void flush() {
        if (!isTerminated) {
            new Thread(flusher).start();
        }
    }

    @Override
    public boolean isReady(){
        return gates.isSDKReadyNow();
    }


}
