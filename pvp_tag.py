# -*- coding: utf-8 -*-
import pyspigot as ps
from org.bukkit import Bukkit
from me.clip.placeholderapi.expansion import PlaceholderExpansion
from me.clip.placeholderapi import PlaceholderAPI
from java.lang import IllegalStateException, Throwable

# Lưu tier gần nhất cho mỗi người chơi
tiers = {}
# ID của task tự động cập nhật
task_id = None

class TierExpansion(PlaceholderExpansion):
    def getIdentifier(self):
        return "tiertag"

    def getAuthor(self):
        return "YourName"

    def getVersion(self):
        return "1.0"

    def onPlaceholderRequest(self, player, identifier):
        # Placeholder động: %tiertag_tier%
        if identifier == "tier":
            return tiers.get(player.getUniqueId(), "N/A")
        return None


def compute_tier(kdr):
    # Xác định tier dựa trên KDR
    if kdr <= 500:
        return "&x&A&3&4&7&0&2LT5"
    elif kdr <= 6000:
        return "&x&D&2&5&D&0&4HT5"
    elif kdr <= 8000:
        return "&x&C&0&B&D&A&2LT4"
    elif kdr <= 10000:
        return "&x&E&B&E&A&C&8HT4"
    elif kdr <= 15000:
        return "&x&1&2&C&6&5&DLT3"
    elif kdr <= 20000:
        return "&x&0&4&F&9&6&AHT3"
    elif kdr <= 25000:
        return "&x&0&2&7&7&D&0LT2"
    elif kdr <= 30000:
        return "&x&2&1&C&9&F&BHT2"
    elif kdr <= 40000:
        return "&x&B&0&0&4&C&ELT1"
    else:
        return "&x&F&9&0&6&E&CHT1"


def update_tiers():
    # Cập nhật tier cho tất cả người chơi online
    for player in Bukkit.getOnlinePlayers():
        try:
            try:
                raw = PlaceholderAPI.setPlaceholders(player, "%hnybpvpelo_elo%")
            except IllegalStateException:
                raw = None
            if not raw or (isinstance(raw, str) and raw.lower() == 'null'):
                kdr = 0.0
            else:
                kdr = float(raw)
        except Throwable as e:
            logger.warning("TierTag: error getting ELO for %s: %s" % (player.getName(), e))
            kdr = 0.0
        tiers[player.getUniqueId()] = compute_tier(kdr)


def start():
    # Called khi script được load
    TierExpansion().register()
    update_tiers()
    global task_id
    # Lên lịch tự động cập nhật mỗi 60 giây (20 ticks)
    task_id = ps.scheduler.scheduleRepeatingTask(update_tiers, 0, 20 * 60)
    logger.info("TierTag script started and scheduleRepeatingTask every 60s (task_id=%s)." % task_id)


def stop():
    # Called khi script unload
    if task_id is not None:
        ps.scheduler.stopTask(task_id)
        logger.info("TierTag script stopped and cancelled task %s." % task_id)
    else:
        logger.info("TierTag script stopped (no task to cancel).")

# Sử dụng placeholder %tiertag_tier% để hiển thị tier trong config hoặc chat
