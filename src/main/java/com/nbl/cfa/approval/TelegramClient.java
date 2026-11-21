package com.nbl.cfa.approval;

public interface TelegramClient {
    void sendMessage(String chatId, String text);
}
