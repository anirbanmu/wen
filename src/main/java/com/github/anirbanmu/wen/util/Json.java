package com.github.anirbanmu.wen.util;

import com.dslplatform.json.DslJson;
import com.dslplatform.json.runtime.Settings;

public final class Json {
    public static final DslJson<Object> DSL = new DslJson<>(Settings.withRuntime().includeServiceLoader());

    private Json() {
    }
}
