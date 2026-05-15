import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TestSpotify {
    public static void main(String[] args) throws Exception {
        String url = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M";
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        String html = response.body();
        
        Matcher m = Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(html);
        if (m.find()) {
            System.out.println("Token found: " + m.group(1).substring(0, 10) + "...");
        } else {
            System.out.println("Token not found");
        }
    }
}
