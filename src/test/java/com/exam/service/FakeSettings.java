package com.exam.service;

import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** A SystemSettingService mock backed by a map, for unit tests of services that read and write settings. */
public final class FakeSettings {

    private FakeSettings() {}

    public static SystemSettingService create(Map<String, String> store) {
        SystemSettingService s = mock(SystemSettingService.class);
        when(s.getSetting(anyString())).thenAnswer(i -> store.get(i.<String>getArgument(0)));
        when(s.getBooleanSetting(anyString(), anyBoolean())).thenAnswer(i -> {
            String v = store.get(i.<String>getArgument(0));
            return v == null ? i.getArgument(1) : Boolean.parseBoolean(v);
        });
        when(s.getSettings(anyCollection())).thenAnswer(i -> {
            Map<String, String> m = new HashMap<>();
            for (Object k : i.<Collection<?>>getArgument(0)) if (store.containsKey(k)) m.put((String) k, store.get(k));
            return m;
        });
        when(s.getAllSettings()).thenAnswer(i -> new HashMap<>(store));
        doAnswer(i -> store.put(i.getArgument(0), i.getArgument(1))).when(s).updateSetting(anyString(), any());
        doAnswer(i -> store.remove(i.<String>getArgument(0))).when(s).deleteSetting(anyString());
        return s;
    }
}
