package com.flippingutilities.model;

import lombok.Data;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
public class BackupCheckpoints {
    Map<String, Instant> accountToBackupTime = new HashMap<>();

    public boolean shouldBackup(String displayName, Instant lastUpdatedAt) {
        if (!accountToBackupTime.containsKey(displayName)) {
            return true;
        }
        Instant backupLastUpdatedTime = accountToBackupTime.get(displayName);
        return !lastUpdatedAt.equals(backupLastUpdatedTime);
    }
}
