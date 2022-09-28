package me.l3st4t.gscpr;

import com.github.alexdlaird.ngrok.NgrokClient;
import com.github.alexdlaird.ngrok.protocol.CreateTunnel;
import com.github.alexdlaird.ngrok.protocol.Proto;
import com.github.alexdlaird.ngrok.protocol.Tunnel;
import com.github.philippheuer.events4j.core.EventManager;
import com.github.philippheuer.events4j.simple.SimpleEventHandler;
import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.TwitchClientBuilder;
import com.github.twitch4j.common.util.EventManagerUtils;
import com.github.twitch4j.common.util.TypeConvert;
import com.github.twitch4j.eventsub.EventSubNotification;
import com.github.twitch4j.eventsub.EventSubSubscriptionStatus;
import com.github.twitch4j.eventsub.EventSubTransport;
import com.github.twitch4j.eventsub.EventSubTransportMethod;
import com.github.twitch4j.eventsub.events.ChannelUpdateEvent;
import com.github.twitch4j.eventsub.events.StreamOnlineEvent;
import com.github.twitch4j.eventsub.subscriptions.SubscriptionTypes;
import com.github.twitch4j.helix.domain.CustomReward;
import com.github.twitch4j.helix.domain.GameList;
import com.github.twitch4j.helix.domain.Stream;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.block.implementation.Section;
import io.javalin.Javalin;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static com.github.twitch4j.eventsub.util.EventSubVerifier.*;

public class GSCPR {

    private static final Logger logger = LogManager.getLogger();

    private final YamlDocument config;
    private final TwitchClient twitchClient;
    private final Multimap<String, CustomReward> rewards = MultimapBuilder.hashKeys().arrayListValues().build();

    public GSCPR() {
        java.util.logging.LogManager.getLogManager().reset();
        try {
            this.config = YamlDocument.create(new File("config.yml"), getClass().getResourceAsStream("/config.yml"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load configuration file!");
        }

        String clientId = config.getString("appId");
        String clientSecret = config.getString("appSecret");
        String broadcasterId = config.getString("broadcasterId");

        this.twitchClient = TwitchClientBuilder.builder()
                .withEnableHelix(true)
                .withClientId(clientId)
                .withClientSecret(clientSecret).build();

        EventManager eventManager = EventManagerUtils.initializeEventManager(SimpleEventHandler.class);

        int port = config.getInt("port");
        Javalin.create(config -> config.server(() -> {
                    Server server = new Server();
                    ServerConnector connector = new ServerConnector(server);
                    connector.setPort(port);
                    server.setConnectors(new Connector[] { connector });
                    return server;
                })).post("/webhooks/callback", ctx -> {
                    final String msgId = ctx.header("Twitch-Eventsub-Message-Id");
                    final String time = ctx.header("Twitch-Eventsub-Message-Timestamp");
                    if (verifyMessageId(msgId)
                            && verifyTimestamp(time)
                            && verifySignature(clientSecret, msgId, time, ctx.bodyAsBytes(), ctx.header("Twitch-Eventsub-Message-Signature"))) {
                        EventSubNotification notification = TypeConvert.jsonToObject(ctx.body(), EventSubNotification.class);

                        if (notification != null) {
                            if (notification.getChallenge() != null)
                                ctx.result(notification.getChallenge());

                            eventManager.publish(notification);
                            ctx.status(200);
                        } else {
                            ctx.status(500);
                        }
                    } else {
                        ctx.status(403);
                    }
                }).start(port);

        String callback;
        boolean ngrok = config.getBoolean("useNgrok");
        if (ngrok) {
            NgrokClient ngrokClient = new NgrokClient.Builder().build();
            CreateTunnel createTunnel = new CreateTunnel.Builder()
                    .withProto(Proto.HTTP)
                    .withAddr(port)
                    .build();

            Tunnel tunnel = ngrokClient.connect(createTunnel);
            callback = tunnel.getPublicUrl().replace("http", "https");
        }else{
            callback = config.getString("callbackUrl");
        }

        callback += "/webhooks/callback";

        twitchClient.getHelix().createEventSubSubscription(null, SubscriptionTypes.STREAM_ONLINE.prepareSubscription(builder ->
                builder.broadcasterUserId(broadcasterId).build(),
                EventSubTransport.builder()
                        .callback(callback)
                        .method(EventSubTransportMethod.WEBHOOK)
                        .secret(clientSecret).build())).execute();

        twitchClient.getHelix().createEventSubSubscription(null, SubscriptionTypes.CHANNEL_UPDATE.prepareSubscription(builder ->
                builder.broadcasterUserId(broadcasterId).build(),
                EventSubTransport.builder()
                        .callback(callback)
                        .method(EventSubTransportMethod.WEBHOOK)
                        .secret(clientSecret).build())).execute();


        String oauthToken = config.getString("oauthToken");
        for (String key : config.getSection("games").getRoutesAsStrings(false)) {
            String gameName = config.getString("games." + key + ".name");
            GameList resultList = twitchClient.getHelix().getGames(oauthToken, null, Arrays.asList(gameName)).execute();
            if (resultList.getGames().isEmpty()) {
                logger.warn("Game " + gameName + " not found.");
                continue;
            }

            String gameId = resultList.getGames().get(0).getId();
            for (String rewardKey : config.getSection("games." + key + ".rewards").getRoutesAsStrings(false)) {
                rewards.put(gameId, loadReward(rewardKey, config.getSection("games." + key + ".rewards." + rewardKey)));
            }

            logger.info("Loaded " + rewards.get(gameId).size() + " channel point rewards for game " + gameName + " (ID: " + gameId + ")");
        }

        eventManager.onEvent(EventSubNotification.class, notif -> {
            if (notif.getSubscription().getStatus() != EventSubSubscriptionStatus.ENABLED) return;

            if (SubscriptionTypes.STREAM_ONLINE.equals(notif.getSubscription().getType())) {
                StreamOnlineEvent event = (StreamOnlineEvent) notif.getEvent();

                List<Stream> streams = twitchClient.getHelix().getStreams(oauthToken, null, null, 1, null, null, Collections.singletonList(event.getBroadcasterUserId()), null).execute().getStreams();
                if (streams.isEmpty()) {
                    return;
                }

                updateRewards(oauthToken, event.getBroadcasterUserId(), streams.get(0).getGameId());
            }

            if (SubscriptionTypes.CHANNEL_UPDATE.equals(notif.getSubscription().getType())) {
                ChannelUpdateEvent event = (ChannelUpdateEvent) notif.getEvent();
                updateRewards(oauthToken, event.getBroadcasterUserId(), event.getCategoryId());
            }
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
}
