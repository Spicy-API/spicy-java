package ai.spicyapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract drift check: every contract fact hard-coded in this artifact must still match the
 * published contract.
 *
 * <p>Why it has to exist: the enums, error codes and limits in an SDK are all constants copied from
 * the contract. When the contract changes and these do not, nothing raises an error - requests
 * still go out, responses still parse, it is only that some new option gets rejected locally as an
 * illegal value, or some new error code is flattened into "unknown error". This kind of failure
 * emits no signal.
 *
 * <p>Why it compares against the published contract rather than the spicy-server repository: this
 * repository is public and spicy-server is not. Giving a public repository's CI a token that can
 * read a private one puts that token somewhere anyone can open a pull request against. The contract
 * is public anyway, so comparing public against public needs no credentials at all.
 */
class ContractDriftTest {

    private static final String LIVE_URL = "https://docs.spicyapi.ai/openapi.yaml";
    private static final Path PINNED = Path.of("contracts", "openapi.yaml");
    private static final Pattern ENUM = Pattern.compile("enum:\\s*\\[([^\\]]+)\\]");

    @Test
    @DisplayName("the pinned contract is byte-identical to the published one")
    void pinnedContractIsCurrent() throws Exception {
        assumeTrue(System.getenv("SPICY_SKIP_LIVE_CONTRACT") == null,
                "SPICY_SKIP_LIVE_CONTRACT is set");
        String live = fetchLiveContract();
        assumeTrue(live != null, "the published contract is unreachable; run with network access to verify drift");

        assertEquals(live, Files.readString(PINNED, StandardCharsets.UTF_8),
                "contracts/openapi.yaml is stale against the published contract.\n"
                        + "refresh it:  curl -sH 'User-Agent: spicyapi-contract-check' " + LIVE_URL
                        + " -o contracts/openapi.yaml\n"
                        + "then re-check every constant this package hard-codes against that diff");
    }

    @Test
    @DisplayName("TaskState matches TaskRecord.state in the contract")
    void taskStatesMatch() throws Exception {
        // UNKNOWN has an empty wire value; it is this client's own fallback rather than part of
        // the contract, so filter it out first.
        Set<String> ours = Arrays.stream(TaskState.values())
                .map(TaskState::wireValue)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(enumAfter("    TaskRecord:", "state:"), ours);
    }

    @Test
    @DisplayName("UploadContentType matches UploadURLRequest.contentType in the contract")
    void uploadContentTypesMatch() throws Exception {
        Set<String> ours = Arrays.stream(UploadContentType.values())
                .map(UploadContentType::mediaType)
                .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(enumAfter("    UploadURLRequest:", "contentType:"), ours);
    }

    @Test
    @DisplayName("a dropped enum value is caught, and the failure names the missing value")
    void theCheckCanGoRed() throws Exception {
        // The counter-proof. A check that can never go red is not a check, and the only way to
        // answer "would it go red" is to actually hand it a contract with an entry removed.
        String mutilated = Files.readString(PINNED, StandardCharsets.UTF_8)
                .replace("enum: [queued, running, succeeded, failed, canceled, expired]",
                        "enum: [queued, running, succeeded, failed, canceled]");
        Set<String> theirs = enumAfter(mutilated, "    TaskRecord:", "state:");
        Set<String> ours = Arrays.stream(TaskState.values())
                .map(TaskState::wireValue)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toCollection(TreeSet::new));

        AssertionError failure = assertThrows(AssertionError.class, () -> assertEquals(theirs, ours));
        assertTrue(failure.getMessage().contains("expired"), "the diff has to name what moved");
    }

    private static Set<String> enumAfter(String anchor, String field) throws IOException {
        return enumAfter(Files.readString(PINNED, StandardCharsets.UTF_8), anchor, field);
    }

    private static Set<String> enumAfter(String contract, String anchor, String field) {
        int start = contract.indexOf(anchor);
        if (start < 0) {
            throw new AssertionError("the contract has no " + anchor.trim() + " schema");
        }
        String window = contract.substring(start, Math.min(start + 4000, contract.length()));
        int at = window.indexOf(field);
        if (at < 0) {
            throw new AssertionError(anchor.trim() + " has no " + field + " property");
        }
        Matcher match = ENUM.matcher(window.substring(at, Math.min(at + 600, window.length())));
        if (!match.find()) {
            throw new AssertionError(anchor.trim() + " " + field + " has no enum");
        }
        return Arrays.stream(match.group(1).split(","))
                .map(value -> value.trim().replaceAll("^['\"]|['\"]$", ""))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Fetches the published contract, returning null when it cannot be reached.
     *
     * <p><b>A User-Agent is mandatory</b>: the docs site's edge protection answers 403 to requests
     * with no UA or a default one, while the api host does not - both were tested on 2026-09-20 and
     * they behave differently. Without that one header this test goes red in the name of "contract
     * drift" for a reason that has nothing to do with the contract.
     */
    private static String fetchLiveContract() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(LIVE_URL))
                .header("User-Agent", "spicyapi-contract-check")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        // No try-with-resources here: HttpClient only implements AutoCloseable from Java 21, and
        // this artifact's floor is 17, where that simply would not compile.
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new AssertionError("the published contract answered " + response.statusCode()
                        + "; a 403 here usually means the User-Agent header was dropped");
            }
            return response.body();
        } catch (IOException | InterruptedException unreachable) {
            if (unreachable instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }
}
