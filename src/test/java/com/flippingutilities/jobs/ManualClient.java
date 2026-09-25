package com.flippingutilities.jobs;

import okhttp3.*;
import okio.Buffer;
import okio.BufferedSource;
import okio.Timeout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

/** Controlled HTTP boundary; requests never reach a socket. */
public class ManualClient extends OkHttpClient {
    public final List<ManualCall> calls = Collections.synchronizedList(new ArrayList<>());
    /** While true, enqueue fails synchronously, like a dispatcher that has been shut down. */
    public boolean rejectEnqueue;
    @Override public Call newCall(Request request) {
        ManualCall call = new ManualCall(request, rejectEnqueue);
        calls.add(call);
        return call;
    }

    public static class ManualCall implements Call {
        private final Request request;
        private final boolean rejectEnqueue;
        private Callback callback;
        private boolean canceled;
        ManualCall(Request request, boolean rejectEnqueue) {
            this.request = request;
            this.rejectEnqueue = rejectEnqueue;
        }
        public TrackingBody respond(int status, String text) throws IOException {
            TrackingBody body = new TrackingBody(text);
            respond(status, (ResponseBody) body);
            return body;
        }
        public void respond(int status, ResponseBody body) throws IOException {
            callback.onResponse(this, new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("test response").body(body).build());
        }
        public void fail() { callback.onFailure(this, new IOException("offline")); }
        @Override public Request request() { return request; }
        @Override public Response execute() { throw new AssertionError("Unexpected synchronous HTTP call"); }
        @Override public void enqueue(Callback callback) {
            if (rejectEnqueue) { throw new RejectedExecutionException(); }
            this.callback = callback;
        }
        @Override public void cancel() { canceled = true; }
        @Override public boolean isExecuted() { return callback != null; }
        @Override public boolean isCanceled() { return canceled; }
        @Override public Timeout timeout() { return new Timeout(); }
        @Override public Call clone() { return new ManualCall(request, rejectEnqueue); }
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
