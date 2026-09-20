package ai.spicyapi;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Reads a response body into a string, refusing one that grows past a byte ceiling.
 *
 * <p>Why not {@code BodyHandlers.ofString}: it buffers whatever arrives, without limit. A broken
 * proxy, a captive portal or a hijacked response can emit bytes indefinitely, and the client would
 * allocate for all of them until the caller's heap is gone. There is no signal before that point —
 * it looks like a slow download.
 *
 * <p>Why not {@code BodyHandlers.ofInputStream} plus a bounded read loop, which is the shorter way
 * to write this: that handler makes {@code send} return as soon as the response headers arrive, so
 * the request timeout no longer covers the body. A stalled body would then hang forever instead of
 * timing out — trading an unbounded allocation for an unbounded wait. Counting inside a subscriber
 * keeps {@code send} waiting on the body, so the existing timeout still applies.
 *
 * <p>The ceiling is checked per chunk rather than after the fact, since checking afterwards means
 * the bytes were already allocated.
 */
final class BoundedResponseBody {

    /** Raised when a response outgrew the ceiling. An {@link IOException} so that the JDK's own
     * unwrapping in {@code HttpClient.send} keeps it intact rather than replacing it. */
    static final class TooLarge extends IOException {

        private static final long serialVersionUID = 1L;

        TooLarge(long limit) {
            super("the API response exceeded the local " + limit + "-byte ceiling");
        }
    }

    private BoundedResponseBody() {
    }

    static HttpResponse.BodyHandler<String> handler(long limit) {
        return info -> new BoundedSubscriber(limit);
    }

    /**
     * Whether a failure was this ceiling rather than a network fault.
     *
     * <p>Walks the cause chain because the JDK may hand the exception back wrapped, and stops after
     * a few links: a self-referencing cause is rare but it would otherwise spin forever, which is a
     * worse failure than the one being diagnosed.
     */
    static boolean tooLarge(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 8; depth++) {
            if (cause instanceof TooLarge) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static final class BoundedSubscriber implements HttpResponse.BodySubscriber<String> {

        private final long limit;
        private final List<ByteBuffer> chunks = new ArrayList<>();
        private final CompletableFuture<String> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private long received;

        BoundedSubscriber(long limit) {
            this.limit = limit;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer item : items) {
                received += item.remaining();
                if (received > limit) {
                    // Hang up immediately and discard what arrived. Checking only after reading
                    // it all would be no ceiling at all - endless output is the very thing being
                    // guarded against.
                    subscription.cancel();
                    chunks.clear();
                    body.completeExceptionally(new TooLarge(limit));
                    return;
                }
                chunks.add(item);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            chunks.clear();
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            if (body.isDone()) {
                // An in-flight completion signal can still arrive after cancelling. The outcome is
                // already settled by then, and allocating another buffer sized by received would
                // just waste memory - received being, at this moment, precisely the number that
                // exceeded the ceiling.
                return;
            }
            // Decode the whole thing at once rather than block by block: a multi-byte character
            // can straddle the boundary between two blocks, and decoding per block would split it
            // into two replacement characters - damage that only ever appears in non-ASCII content,
            // which is to say only in error messages written in other languages.
            byte[] joined = new byte[(int) received];
            int at = 0;
            for (ByteBuffer chunk : chunks) {
                int length = chunk.remaining();
                chunk.get(joined, at, length);
                at += length;
            }
            chunks.clear();
            body.complete(new String(joined, 0, at, StandardCharsets.UTF_8));
        }

        @Override
        public CompletionStage<String> getBody() {
            return body;
        }
    }
}
