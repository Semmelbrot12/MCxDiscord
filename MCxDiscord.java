package emma;

import net.dv8tion.jda.api.requests.GatewayIntent;
import java.util.EnumSet;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.plugin.java.JavaPlugin;

public class MCxDiscord extends JavaPlugin {
    private static JDA jda;
    private static String channelId;
    private static String token;

    @Override
    public void onEnable() {
        saveDefaultConfig(); // Ensure the config is saved to the disk
        loadConfig(); // Load the configuration from the file

        // Try to initialize the bot
        if (loadBot()) {
            Bukkit.getPluginManager().registerEvents(new MinecraftListener(), this);
        } else {
            getLogger().severe("Failed to load bot. Make sure the config.yml contains the token and channelId.");
        }
    }

    @Override
    public void onDisable() {
        if (jda != null) {
            MessageChannel channel = jda.getTextChannelById(channelId);
            if (channel != null) {
                channel.sendMessage(":red_circle: Server is stopping").queue();
            }
            jda.shutdownNow();
        }
    }

    private boolean loadBot() {
        try {
            token = getConfig().getString("token");
            channelId = getConfig().getString("channelId");

            if (token == null || channelId == null) {
                return false;
            }

            jda = JDABuilder.createDefault(token)
                    .enableIntents(EnumSet.of(
                            GatewayIntent.GUILD_MESSAGES,
                            GatewayIntent.MESSAGE_CONTENT,
                            GatewayIntent.GUILD_MEMBERS
                    ))
                    .addEventListeners(new DiscordListener())
                    .build()
                    .awaitReady();

            return true;
        } catch (Exception e) {
            getLogger().severe("Failed to start Discord bot: " + e.getMessage());
            return false;
        }
    }

    private void loadConfig() {
        saveDefaultConfig(); // Save config if it doesn't exist
        reloadConfig(); // Reload the config if needed
    }

    private static class DiscordListener extends ListenerAdapter {
        private static final String TARGET_CHANNEL_ID = channelId;

        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            if (!event.isFromGuild() || !event.getChannel().getId().equals(TARGET_CHANNEL_ID)) {
                return;  // Only process messages from the correct channel
            }

            User author = event.getAuthor();
            if (author.isBot()) return;  // Ignore messages from bots

            // Get the message content
            String msg = event.getMessage().getContentDisplay();
            String name = event.getMember() != null ? event.getMember().getEffectiveName() : author.getName();
            String fullMsg = "<" + name + "> " + msg;

            for (Player player : Bukkit.getServer().getOnlinePlayers()) {
                player.sendMessage(fullMsg); // Send the formatted message to players
            }
        }
    }

    private static class MinecraftListener implements Listener {
        @EventHandler
        public void onChat(AsyncChatEvent event) {
            Player player = event.getPlayer();
            String msg = PlainTextComponentSerializer.plainText().serialize(event.message());
            MessageChannel channel = jda.getTextChannelById(channelId);
            if (channel != null) {
                String fullMessage = player.getName() + "\n> " + msg;
                channel.sendMessage(fullMessage).queue(); // Send the message to Discord
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig(); // Reload the plugin's config

            if (jda != null) jda.shutdownNow(); // Shut down the previous JDA instance

            if (loadBot()) { // Try to load the bot again
                sender.sendMessage("§aMCxDiscord reloaded successfully.");
            } else {
                sender.sendMessage("§cFailed to reload MCxDiscord. Check config.yml (token or channelID missing).");
            }

            return true;
        }
        return false;
    }
}
