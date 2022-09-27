package me.l3st4t.gscpr;

import com.github.philippheuer.credentialmanager.domain.OAuth2Credential;
import com.github.philippheuer.events4j.simple.SimpleEventHandler;
import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.TwitchClientBuilder;
import com.github.twitch4j.events.ChannelChangeGameEvent;
import com.github.twitch4j.events.ChannelGoLiveEvent;
import com.github.twitch4j.helix.domain.CustomReward;
import com.github.twitch4j.helix.domain.GameList;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.block.implementation.Section;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collection;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class GSCPR {

    private static final Logger logger = LogManager.getLogger();

    private final YamlDocument config;
    private final TwitchClient twitchClient;
    private final Multimap<String, CustomReward> rewards = MultimapBuilder.hashKeys().arrayListValues().build();

    public GSCPR() {
        try {
            this.config = YamlDocument.create(new File("config.yml"), getClass().getResourceAsStream("/config.yml"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load configuration file!");
        }

        String oauthToken = config.getString("oauthToken");
        String broadcasterId = config.getString("broadcasterId");
        String clientId = config.getString("clientId");

        this.twitchClient = TwitchClientBuilder.builder()
                .withEnableHelix(true)
                .withClientId(clientId)
                .withDefaultEventHandler(SimpleEventHandler.class)
                .withDefaultAuthToken(new OAuth2Credential("twitch", oauthToken)).build();

        this.twitchClient.getClientHelper().enableStreamEventListener(config.getString("channelName"));

        for (String key : config.getSection("games").getRoutesAsStrings(false)) {
            String gameName = config.getString("games." + key + ".name");
            GameList resultList = twitchClient.getHelix().getGames(oauthToken, null, Arrays.asList(gameName)).execute();
            if (resultList.getGames().isEmpty()) {
                continue;
            }

            String gameId = resultList.getGames().get(0).getId();
            for (String rewardKey : config.getSection("games." + key + ".rewards").getRoutesAsStrings(false)) {
                rewards.put(gameId, loadReward(rewardKey, config.getSection("games." + key + ".rewards." + rewardKey)));
            }

            logger.info("Loaded " + rewards.get(gameId).size() + " channel point rewards for game " + gameName + " (ID: " + gameId + ")");
        }

        twitchClient.getEventManager().onEvent(ChannelChangeGameEvent.class, event -> {
            logger.info("Game change detected! Updating rewards.");
            updateRewards(oauthToken, broadcasterId, event.getGameId());
        });

        twitchClient.getEventManager().onEvent(ChannelGoLiveEvent.class, event -> {
            logger.info("Channel is going live! Updating rewards.");
            updateRewards(oauthToken, broadcasterId, event.getStream().getGameId());
        });
    }

    private CustomReward loadReward(String rewardKey, Section section) {
        String title = section.getString("title");
        boolean skipRequestQueue = section.getBoolean("skipRequestQueue");
        String prompt = section.getString("prompt");
        boolean userInputRequired = section.getBoolean("userInputRequired");
        int cost = section.getInt("cost");

        return CustomReward.builder()
                .id(rewardKey)
                .title(title)
                .shouldRedemptionsSkipRequestQueue(skipRequestQueue)
                .prompt(prompt)
                .isUserInputRequired(userInputRequired)
                .cost(cost).build();
    }

    private void updateRewards(String oauthToken, String broadcasterId, String gameId) {
        for (CustomReward customReward : twitchClient.getHelix().getCustomRewards(oauthToken, broadcasterId, null, true).execute().getRewards()) {
            if (rewards.values().stream().anyMatch(otherReward -> otherReward.getTitle().equalsIgnoreCase(customReward.getTitle()))) {
                logger.info("Deleting reward " + customReward.getId());
                twitchClient.getHelix().deleteCustomReward(oauthToken, broadcasterId, customReward.getId()).execute();
            }
        }

        if (rewards.containsKey(gameId)) {
            Collection<CustomReward> customRewards = rewards.get(gameId);
            for (CustomReward reward : customRewards) {
                twitchClient.getHelix().createCustomReward(oauthToken, broadcasterId, reward).queue();
            }
        }

    }

    public Stream<Character> getRandomSpecialChars(int count) {
        Random random = new SecureRandom();
        IntStream specialChars = random.ints(count, 33, 45);
        return specialChars.mapToObj(data -> (char) data);
    }

    // Method to generate a random alphanumeric password of a specific length
    public static String generateRandomPassword(int len)
    {
        // ASCII range – alphanumeric (0-9, a-z, A-Z)
        final String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

        SecureRandom random = new SecureRandom();

        // each iteration of the loop randomly chooses a character from the given
        // ASCII range and appends it to the `StringBuilder` instance
        return IntStream.range(0, len)
                .map(i -> random.nextInt(chars.length()))
                .mapToObj(randomIndex -> String.valueOf(chars.charAt(randomIndex)))
                .collect(Collectors.joining());
    }
}
