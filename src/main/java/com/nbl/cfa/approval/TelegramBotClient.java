package com.nbl.cfa.approval;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = "cfa.telegram.enabled", havingValue = "true")
public class TelegramBotClient implements TelegramClient {

    private final String botToken;
    private final String defaultChatId;
    private final JsonMapper jsonMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public TelegramBotClient(@Value("${cfa.telegram.bot-token:}") String botToken,
                             @Value("${cfa.telegram.chat-id:}") String defaultChatId,
                             JsonMapper jsonMapper) {
        this.botToken = botToken;
        this.defaultChatId = defaultChatId;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void sendMessage(String chatId, String text) {
        String target = chatId == null || chatId.isBlank() ? defaultChatId : chatId;
        if (botToken.isBlank() || target.isBlank()) {
            throw new IllegalStateException("Thiếu Telegram bot token hoặc chat id");
        }
        String body = jsonMapper.writeValueAsString(new Message(target, text));
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "https://api.telegram.org/bot" + botToken + "/sendMessage"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Telegram sendMessage thất bại: HTTP " + response.statusCode());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Không gọi được Telegram", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Gọi Telegram bị interrupt", e);
        }
    }

    private record Message(String chat_id, String text) {
    }
}
