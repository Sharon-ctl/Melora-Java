import dev.lavalink.youtube.YoutubeAudioSourceManager;
import java.lang.reflect.Method;
public class TestOauth {
    public static void main(String[] args) {
        for (Method m : YoutubeAudioSourceManager.class.getMethods()) {
            if (m.getName().toLowerCase().contains("oauth")) {
                System.out.println(m.getName());
            }
        }
    }
}
