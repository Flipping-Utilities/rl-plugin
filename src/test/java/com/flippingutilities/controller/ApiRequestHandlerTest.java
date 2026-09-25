package com.flippingutilities.controller;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import okhttp3.*;
import okio.Buffer;
import okio.BufferedSource;
import okio.Timeout;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;

public class ApiRequestHandlerTest {
    @Test
    public void malformedResponsesCompleteExceptionallyAndClose() throws Exception {
        for (String body : new String[]{"{ malformed", "{\"data\":1}", "null"}) {
            ManualClient client = new ManualClient();
            ApiRequestHandler handler = handler(client);
            CompletableFuture<ApiResponse<Integer>> future = request(handler);
            TrackingBody responseBody = client.calls.get(0).respond(200, body);
            assertTrue("Invalid response must complete: " + body, future.isCompletedExceptionally());
            assertTrue(responseBody.closed);
        }
    }

    @Test
    public void successAndApiErrorsKeepTheirExistingOutcomesAndClose() throws Exception {
        ManualClient client = new ManualClient();
        ApiRequestHandler handler = handler(client);
        CompletableFuture<ApiResponse<Integer>> success = request(handler);
        assertTrue(client.calls.get(0).respond(200, "{\"data\":7,\"errors\":[]}").closed);
        assertEquals(Integer.valueOf(7), success.join().data);

        CompletableFuture<ApiResponse<Integer>> apiError = request(handler);
        assertTrue(client.calls.get(1).respond(200, "{\"errors\":[{\"message\":\"rejected\"}]}").closed);
        assertTrue(apiError.isCompletedExceptionally());

        CompletableFuture<ApiResponse<Integer>> httpError = request(handler);
        assertTrue(client.calls.get(2).respond(503, "unavailable").closed);
        assertTrue(httpError.isCompletedExceptionally());
    }

    private ApiRequestHandler handler(ManualClient client) {
        FlippingPlugin plugin = new FlippingPlugin() {
            @Override public OkHttpClient getHttpClient() { return client; }
        };
        plugin.gson = new Gson();
        ApiRequestHandler handler = new ApiRequestHandler(plugin);
        handler.httpClient = client;
        return handler;
    }

    private CompletableFuture<ApiResponse<Integer>> request(ApiRequestHandler handler) {
        return handler.getResponseFuture(new Request.Builder().url("https://test.invalid/api").build(),
            new TypeToken<ApiResponse<Integer>>() {});
    }

    /** HTTP boundary shared with wiki tests; requests never reach a socket. */
    public static class ManualClient extends OkHttpClient {
        public final List<ManualCall> calls = new ArrayList<>();
        @Override public Call newCall(Request request) {
            ManualCall call = new ManualCall(request);
            calls.add(call);
            return call;
        }
    }

    public static class ManualCall implements Call {
        private final Request request;
        private Callback callback;
        private boolean canceled;
        ManualCall(Request request) { this.request = request; }
        public TrackingBody respond(int status, String text) throws IOException {
            TrackingBody body = new TrackingBody(text);
            callback.onResponse(this, new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("test response").body(body).build());
            return body;
        }
        public void fail() { callback.onFailure(this, new IOException("offline")); }
        @Override public Request request() { return request; }
        @Override public Response execute() { throw new AssertionError("Unexpected synchronous HTTP call"); }
        @Override public void enqueue(Callback callback) { this.callback = callback; }
        @Override public void cancel() { canceled = true; }
        @Override public boolean isExecuted() { return callback != null; }
        @Override public boolean isCanceled() { return canceled; }
        @Override public Timeout timeout() { return new Timeout(); }
        @Override public Call clone() { return new ManualCall(request); }
    }

    public static class TrackingBody extends ResponseBody {
        private final Buffer buffer = new Buffer();
        public boolean closed;
        TrackingBody(String text) { buffer.writeUtf8(text); }
        @Override public MediaType contentType() { return MediaType.parse("application/json"); }
        @Override public long contentLength() { return -1; }
        @Override public BufferedSource source() { return buffer; }
        @Override public void close() { closed = true; super.close(); }
    }
}
