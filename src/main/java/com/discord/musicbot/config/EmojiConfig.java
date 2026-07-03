package com.discord.musicbot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

public class EmojiConfig {
    private static final Logger logger = LoggerFactory.getLogger(EmojiConfig.class);
    private static final File FILE = new File("emojis.json");
    private static final ObjectMapper mapper = new ObjectMapper();

    public String success = "<:success1:1461351761607393453>";
    public String error = "<:error1:1461351972924817552>";
    public String pause = "<:pause1:1461362470370152509>";
    public String play = "<:play1:1461362473130135746>";
    public String skip = "<:skip1:1461362487327981588>";
    public String stop = "<:stop1:1461351823477833860>";
    public String previous = "<:previous:1422048593614864477>";
    public String volume = "<:volume1:1461352140160237713>";
    public String time = "<:time1:1461362493803855975>";
    public String shuffle = "<:shuffle1:1461362482869174416>";
    public String repeat = "<:repeat1:1461362480566636544>";
    
    public String btnLoop = "<:loop3:1504784020020527105>";
    public String btnPrevious = "<:previous3:1504769078374694962>";
    public String btnResume = "<:resume3:1504769080346021898>";
    public String btnPause = "<:pause3:1504769082640171018>";
    public String btnSkip = "<:skip3:1504769076189462619>";
    public String btnFavorite = "<:favorite3:1507109816978505788>";
    public String btnStop = "<:stop3:1504769073517564015>";
    public String btnVolumeUp = "<:volumeup3:1507681018914541668>";
    public String btnVolumeDown = "<:volumedown3:1507681020957425844>";
    public String btnShuffle = "<:shuffle3:1507681023062839306>";
    public String btnQueue = "<:music3:1504821132044402699>";
    
    public String queuedBy = "<:queuedby3:1504769635420082248>";
    public String duration = "<:duration3:1504769632441991238>";
    public String addMusic = "<:addmusic3:1504821095201505390>";
    public String music = "<:music3:1504821132044402699>";
    public String mewsic = "<:mewsic:1519630142719135794>";
    
    public String enabled = "<:success1:1461351761607393453>";
    public String disabled = "<:error1:1461351972924817552>";
    public String settings = "<:settings1:1461362482869174416>";

    private static EmojiConfig instance;

    public static synchronized EmojiConfig getInstance() {
        if (instance == null) {
            if (FILE.exists()) {
                try {
                    instance = mapper.readValue(FILE, EmojiConfig.class);
                    // Write back to ensure any newly added emoji fields are appended to the file
                    mapper.writerWithDefaultPrettyPrinter().writeValue(FILE, instance);
                } catch (IOException e) {
                    logger.error("Failed to load emojis.json, using defaults.", e);
                    instance = new EmojiConfig();
                }
            } else {
                instance = new EmojiConfig();
                try {
                    mapper.writerWithDefaultPrettyPrinter().writeValue(FILE, instance);
                } catch (IOException e) {
                    logger.error("Failed to save emojis.json", e);
                }
            }
        }
        return instance;
    }
}
