package com.discord.musicbot.utils;

import com.discord.musicbot.data.model.UserStats;
import net.dv8tion.jda.api.entities.User;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

public class CanvasHelper {

    private static final int WIDTH = 800;
    private static final int HEIGHT = 500;
    
    // Soft baby pink and white theme
    private static final Color BABY_PINK = new Color(255, 228, 235);
    private static final Color WHITE = Color.WHITE;
    private static final Color TEXT_DARK = new Color(70, 70, 70);
    private static final Color PANEL_BG = new Color(255, 255, 255, 180);

    public static byte[] generateStatsImage(User user, UserStats stats) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        
        setupGraphics(g);
        drawBackground(g);
        
        try {
            drawHeader(g, user, "Listening Stats");
            
            // Draw Main Stats Panel
            g.setColor(PANEL_BG);
            g.fillRoundRect(50, 150, 700, 80, 20, 20);
            
            g.setColor(TEXT_DARK);
            g.setFont(new Font("SansSerif", Font.BOLD, 20));
            long totalSeconds = stats.getTotalListeningTimeMs() / 1000;
            long hours = totalSeconds / 3600;
            long minutes = (totalSeconds % 3600) / 60;
            
            g.drawString(String.format("Total Time: %dh %dm", hours, minutes), 80, 195);
            g.drawString(String.format("Tracks Played: %d", stats.getTotalTracksPlayed()), 350, 195);
            
            // Draw Top Artists Panel
            g.setColor(PANEL_BG);
            g.fillRoundRect(50, 250, 330, 200, 20, 20);
            g.setColor(TEXT_DARK);
            g.setFont(new Font("SansSerif", Font.BOLD, 22));
            g.drawString("Top Artists", 70, 285);
            
            g.setFont(new Font("SansSerif", Font.PLAIN, 18));
            List<Map.Entry<String, Long>> artistList = new ArrayList<>(stats.getFavoriteArtists().entrySet());
            artistList.sort((a, b) -> b.getValue().compareTo(a.getValue()));
            int count = 0;
            int y = 320;
            for (Map.Entry<String, Long> entry : artistList) {
                g.drawString(String.format("%d. %s", ++count, entry.getKey()), 70, y);
                g.drawString(String.valueOf(entry.getValue()), 320, y);
                y += 35;
                if (count >= 4) break;
            }
            if (count == 0) g.drawString("None yet", 70, y);
            
            // Draw Top Tracks Panel
            g.setColor(PANEL_BG);
            g.fillRoundRect(420, 250, 330, 200, 20, 20);
            g.setColor(TEXT_DARK);
            g.setFont(new Font("SansSerif", Font.BOLD, 22));
            g.drawString("Top Tracks", 440, 285);
            
            g.setFont(new Font("SansSerif", Font.PLAIN, 18));
            List<Map.Entry<String, Long>> trackList = new ArrayList<>(stats.getFavoriteTracks().entrySet());
            trackList.sort((a, b) -> b.getValue().compareTo(a.getValue()));
            count = 0;
            y = 320;
            for (Map.Entry<String, Long> entry : trackList) {
                String title = entry.getKey();
                if (title.length() > 22) title = title.substring(0, 20) + "...";
                g.drawString(String.format("%d. %s", ++count, title), 440, y);
                g.drawString(String.valueOf(entry.getValue()), 690, y);
                y += 35;
                if (count >= 4) break;
            }
            if (count == 0) g.drawString("None yet", 440, y);
            
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            g.dispose();
        }
        
        return toByteArray(image);
    }

    public static byte[] generateWrappedImage(User user, UserStats stats, String botName) {
        BufferedImage image = new BufferedImage(WIDTH, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        
        setupGraphics(g);
        
        // Custom background for wrapped (diagonal gradient)
        GradientPaint gp = new GradientPaint(0, 0, BABY_PINK, WIDTH, 600, WHITE);
        g.setPaint(gp);
        g.fillRect(0, 0, WIDTH, 600);
        
        try {
            drawHeader(g, user, botName + " Wrapped");
            
            // Highlight Box
            g.setColor(PANEL_BG);
            g.fillRoundRect(50, 150, 700, 100, 25, 25);
            
            long totalSeconds = stats.getTotalListeningTimeMs() / 1000;
            long hours = totalSeconds / 3600;
            long minutes = (totalSeconds % 3600) / 60;
            
            g.setColor(TEXT_DARK);
            g.setFont(new Font("SansSerif", Font.BOLD, 28));
            g.drawString("You listened for", 80, 190);
            
            g.setColor(new Color(230, 100, 150)); // Darker pink for emphasis
            g.setFont(new Font("SansSerif", Font.BOLD, 36));
            g.drawString(String.format("%dh %dm", hours, minutes), 80, 230);
            
            g.setColor(TEXT_DARK);
            g.setFont(new Font("SansSerif", Font.BOLD, 22));
            g.drawString("Total Tracks: " + stats.getTotalTracksPlayed(), 480, 195);
            g.drawString("DJ Points: " + stats.getDjPoints(), 480, 225);
            
            // Top lists
            g.setColor(PANEL_BG);
            g.fillRoundRect(50, 270, 700, 280, 25, 25);
            
            g.setColor(TEXT_DARK);
            g.setFont(new Font("SansSerif", Font.BOLD, 24));
            g.drawString("Your Top Artists", 80, 310);
            g.drawString("Your Top Tracks", 420, 310);
            
            g.setFont(new Font("SansSerif", Font.PLAIN, 18));
            
            // Artists
            List<Map.Entry<String, Long>> artistList = new ArrayList<>(stats.getFavoriteArtists().entrySet());
            artistList.sort((a, b) -> b.getValue().compareTo(a.getValue()));
            int count = 0;
            int y = 350;
            for (Map.Entry<String, Long> entry : artistList) {
                g.drawString(String.format("%d. %s", ++count, entry.getKey()), 80, y);
                y += 40;
                if (count >= 5) break;
            }
            if (count == 0) g.drawString("None yet", 80, y);
            
            // Tracks
            List<Map.Entry<String, Long>> trackList = new ArrayList<>(stats.getFavoriteTracks().entrySet());
            trackList.sort((a, b) -> b.getValue().compareTo(a.getValue()));
            count = 0;
            y = 350;
            for (Map.Entry<String, Long> entry : trackList) {
                String title = entry.getKey();
                if (title.length() > 25) title = title.substring(0, 22) + "...";
                g.drawString(String.format("%d. %s", ++count, title), 420, y);
                y += 40;
                if (count >= 5) break;
            }
            if (count == 0) g.drawString("None yet", 420, y);
            
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            g.dispose();
        }
        
        return toByteArray(image);
    }
    
    private static void setupGraphics(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }
    
    private static void drawBackground(Graphics2D g) {
        GradientPaint gp = new GradientPaint(0, 0, BABY_PINK, WIDTH, HEIGHT, WHITE);
        g.setPaint(gp);
        g.fillRect(0, 0, WIDTH, HEIGHT);
    }
    
    private static void drawHeader(Graphics2D g, User user, String titleText) throws Exception {
        // Fetch Avatar
        String avatarUrlStr = user.getAvatarUrl() != null ? user.getAvatarUrl() : user.getDefaultAvatarUrl();
        // Force PNG format and size 128
        if (avatarUrlStr != null) {
            avatarUrlStr = avatarUrlStr.replace(".webp", ".png") + "?size=128";
            BufferedImage avatar = null;
            try {
                java.net.URLConnection conn = new URI(avatarUrlStr).toURL().openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
                try (java.io.InputStream in = conn.getInputStream()) {
                    avatar = ImageIO.read(in);
                }
            } catch (Exception e) {
                // If avatar fetch fails, proceed without crashing
                avatar = null;
            }
            
            if (avatar != null) {
                // Create rounded avatar
                BufferedImage roundedAvatar = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2 = roundedAvatar.createGraphics();
                setupGraphics(g2);
                g2.fill(new RoundRectangle2D.Float(0, 0, 100, 100, 100, 100)); // Circle
                g2.setComposite(AlphaComposite.SrcIn);
                g2.drawImage(avatar, 0, 0, 100, 100, null);
                g2.dispose();
                
                // Draw shadow
                g.setColor(new Color(0, 0, 0, 30));
                g.fillOval(52, 22, 100, 100);
                
                // Draw Avatar
                g.drawImage(roundedAvatar, 50, 20, null);
            }
        }
        
        // Draw User Name
        g.setColor(TEXT_DARK);
        g.setFont(new Font("SansSerif", Font.BOLD, 36));
        g.drawString(user.getEffectiveName(), 170, 65);
        
        // Draw Subtitle
        g.setColor(new Color(120, 120, 120));
        g.setFont(new Font("SansSerif", Font.PLAIN, 24));
        g.drawString(titleText, 170, 100);
    }
    
    private static byte[] toByteArray(BufferedImage image) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            e.printStackTrace();
            return new byte[0];
        }
    }
}
