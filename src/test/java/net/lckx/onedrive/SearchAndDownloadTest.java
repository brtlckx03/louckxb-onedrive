package net.lckx.onedrive;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SearchAndDownloadTest {

    private static SearchAndDownload stubbed(Map<String, String> responses) {
        return new SearchAndDownload() {
            @Override
            String get(HttpClient client, String url, String bearerToken) {
                return responses.getOrDefault(url, "{\"error\":{\"code\":\"notFound\"}}");
            }
        };
    }

    @Test
    void fetchAllItems_singlePage_returnsAllItems() throws Exception {
        String response = "{\"value\":[{\"name\":\"a\"},{\"name\":\"b\"},{\"name\":\"c\"}]}";

        List<String> items = stubbed(Map.of("https://graph/test", response))
                .fetchAllItems(null, "https://graph/test", "token");

        assertEquals(3, items.size());
    }

    @Test
    void fetchAllItems_followsNextLink() throws Exception {
        String page1 = "{\"value\":[{\"name\":\"a\"},{\"name\":\"b\"},{\"name\":\"c\"}],"
                + "\"@odata.nextLink\":\"https://graph/test?$skip=3\"}";
        String page2 = "{\"value\":[{\"name\":\"d\"},{\"name\":\"e\"}]}";
        var sad = stubbed(Map.of("https://graph/test", page1, "https://graph/test?$skip=3", page2));

        List<String> items = sad.fetchAllItems(null, "https://graph/test", "token");

        assertEquals(5, items.size());
        assertEquals("a", sad.jsonString(items.get(0), "name"));
        assertEquals("e", sad.jsonString(items.get(4), "name"));
    }

    @Test
    void fetchAllItems_accumulatesAcrossMultiplePages() throws Exception {
        // Regression test: 728-item folder returned only 200 because nextLink was not followed
        String page1 = buildPage(200, "https://graph/page2");
        String page2 = buildPage(200, "https://graph/page3");
        String page3 = buildPage(200, "https://graph/page4");
        String page4 = buildPage(128, null);

        List<String> items = stubbed(Map.of(
                "https://graph/page1", page1,
                "https://graph/page2", page2,
                "https://graph/page3", page3,
                "https://graph/page4", page4
        )).fetchAllItems(null, "https://graph/page1", "token");

        assertEquals(728, items.size());
    }

    @Test
    void fetchAllItems_returnsNullOnErrorResponse() throws Exception {
        String error = "{\"error\":{\"code\":\"itemNotFound\",\"message\":\"Not found.\"}}";

        List<String> items = stubbed(Map.of("https://graph/test", error))
                .fetchAllItems(null, "https://graph/test", "token");

        assertNull(items);
    }

    @Test
    void fetchAllItems_returnsEmptyListForEmptyFolder() throws Exception {
        String response = "{\"value\":[]}";

        List<String> items = stubbed(Map.of("https://graph/test", response))
                .fetchAllItems(null, "https://graph/test", "token");

        assertNotNull(items);
        assertEquals(0, items.size());
    }

    private String buildPage(int count, String nextLink) {
        var sb = new StringBuilder("{\"value\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"name\":\"item").append(i).append("\"}");
        }
        sb.append("]");
        if (nextLink != null) {
            sb.append(",\"@odata.nextLink\":\"").append(nextLink).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }
}
