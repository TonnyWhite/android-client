package io.split.android.client.network;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;

public class HttpRequestImpl implements HttpRequest {

    private URI mUri;
    private String mBody;
    private HttpMethod mHttpMethod;
    private Map<String, String> mHeaders;

    HttpRequestImpl(URI uri, HttpMethod httpMethod, String body, Map<String, String> headers) {
        mUri = uri;
        mHttpMethod = httpMethod;
        mBody = body;
        mHeaders = new HashMap<>(checkNotNull(headers));
    }

    @Override
    public HttpResponse execute() throws HttpException {

        switch (mHttpMethod) {
            case GET:
                return getRequest();
            case POST: {
                try {
                    return postRequest();
                } catch (IOException e) {
                    throw new HttpException("Error serializing request body: " + e.getLocalizedMessage());
                }
            }
            default:
                throw new IllegalArgumentException("Request HTTP Method not valid: " + mHttpMethod.name());
        }
    }

    private HttpResponse getRequest() throws HttpException {
        URL url;
        HttpURLConnection connection;
        HttpResponse response;
        try {
            url = mUri.toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(mHttpMethod.name());
            addHeaders(connection);
            response = buildResponse(connection);
            connection.disconnect();
        } catch (MalformedURLException e) {
            throw new HttpException("URL is malformed: " + e.getLocalizedMessage());
        } catch (ProtocolException e) {
            throw new HttpException("Http method not allowed: " + e.getLocalizedMessage());
        } catch (IOException e) {
            throw new HttpException("Something happened while retrieving data: " + e.getLocalizedMessage());
        }
        return response;
    }

    private HttpResponse postRequest() throws IOException {

        URL url = mUri.toURL();

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        addHeaders(connection);
        connection.setRequestMethod(mHttpMethod.name());
        if(mBody != null && !mBody.isEmpty()) {
            connection.setDoOutput(true);
            OutputStream bodyStream = null;
            try {
                bodyStream = connection.getOutputStream();
                bodyStream.write(mBody.getBytes());
                bodyStream.flush();
            } finally {
                if(bodyStream != null) {
                    bodyStream.close();
                }
            }
        }
        HttpResponse httpResponse = buildResponse(connection);
        connection.disconnect();
        return httpResponse;
    }

    private void addHeaders(HttpURLConnection connection) {
        for (Map.Entry<String, String> entry : mHeaders.entrySet()) {
            connection.setRequestProperty(entry.getKey(), entry.getValue());
        }
    }

    private HttpResponse buildResponse(HttpURLConnection connection) throws IOException {
        int responseCode = connection.getResponseCode();
        if (responseCode >= HttpURLConnection.HTTP_OK && responseCode < 300) {
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    connection.getInputStream()));

            String inputLine;
            StringBuilder responseData = new StringBuilder();
            while ((inputLine = in.readLine()) != null) {
                responseData.append(inputLine);
            }
            in.close();

            return new HttpResponseImpl(responseCode, (responseData.length() > 0 ? responseData.toString() : null));
        }
        return new HttpResponseImpl(responseCode);
    }

}
