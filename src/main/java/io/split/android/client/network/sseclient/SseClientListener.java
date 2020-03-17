package io.split.android.client.network.sseclient;

import java.util.Map;

public interface SseClientListener {
    void onOpen();
    void onMessage(Map<String, String> values);
    void onError();
}
