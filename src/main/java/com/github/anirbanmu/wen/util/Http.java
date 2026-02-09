package com.github.anirbanmu.wen.util;

import java.net.http.HttpClient;
import java.util.concurrent.Executors;

public final class Http {
    public static final HttpClient CLIENT = HttpClient.newBuilder()
        .executor(Executors.newVirtualThreadPerTaskExecutor())
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private Http() {
    }
}
