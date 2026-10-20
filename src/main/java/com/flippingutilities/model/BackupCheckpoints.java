package com.flippingutilities.model;

import lombok.Data;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
public class BackupCheckpoints {
    Map<String, Instant> accountToBackupTime = new HashMap<>();

    public boolean shouldBackup(String displayName, Instant lastUpdatedAt) {
        //the map can be null if the checkpoint file explicitly contained null and lastUpdatedAt can be null
        //for data that was never stored; neither should abort startup with an NPE
        if (accountToBackupTime == null || !accountToBackupTime.containsKey(displayName)) {
            return true;
        }
        Instant backupLastUpdatedTime = accountToBackupTime.get(displayName);
        return lastUpdatedAt == null || !lastUpdatedAt.equals(backupLastUpdatedTime);
    }
}
