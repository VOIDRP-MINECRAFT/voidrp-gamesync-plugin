package ru.voidrp.gamesync.service;

import java.util.UUID;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.MetaNode;
import net.luckperms.api.node.types.PrefixNode;
import net.luckperms.api.node.types.SuffixNode;
import net.luckperms.api.LuckPermsProvider;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import ru.voidrp.gamesync.config.GameSyncConfig;
import ru.voidrp.gamesync.config.NationRegistry;
import ru.voidrp.gamesync.model.NationDefinition;
import ru.voidrp.gamesync.store.PluginDataStore;

public final class LuckPermsNationMetaService {

    private final JavaPlugin plugin;
    private final NationRegistry registry;
    private final PluginDataStore dataStore;
    private final GameSyncConfig config;
    private LuckPerms luckPerms;

    public LuckPermsNationMetaService(
        JavaPlugin plugin,
        NationRegistry registry,
        PluginDataStore dataStore,
        GameSyncConfig config
    ) {
        this.plugin = plugin;
        this.registry = registry;
        this.dataStore = dataStore;
        this.config = config;
        this.luckPerms = resolveLuckPerms();
    }

    public void reload() {
        this.luckPerms = resolveLuckPerms();
    }

    public void reconcileOnlinePlayers() {
        if (!config.isLuckPermsEnabled() || luckPerms == null) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyForPlayer(player);
        }
    }

    public void applyForPlayer(Player player) {
        if (!config.isLuckPermsEnabled() || luckPerms == null || player == null) return;

        NationDefinition nation = registry.findByPlayer(player.getName());
        String role = nation != null ? nation.roleFor(player.getName()) : null;

        try {
            User user = luckPerms.getUserManager().loadUser(player.getUniqueId()).join();
            if (user == null) return;

            removePreviousManagedMeta(user, player.getUniqueId());

            if (nation == null || role == null) {
                dataStore.clearNationMeta(player.getUniqueId());
                dataStore.saveNow();
                luckPerms.getUserManager().saveUser(user);
                return;
            }

            String customPrefix = nation.customPrefixFor(player.getName());
            String prefix = customPrefix != null && !customPrefix.isBlank()
                ? buildCustomPrefix(nation, role, customPrefix)
                : buildPrefix(nation, role);
            String suffix = buildSuffix(nation, role);
            ImmutableContextSet context = buildContext();

            if (config.isPrefixEnabled() && prefix != null && !prefix.isBlank()) {
                PrefixNode prefixNode = PrefixNode.builder(prefix, config.getPrefixPriority()).withContext(context).build();
                user.data().add(prefixNode);
            }

            if (config.isSuffixEnabled() && suffix != null && !suffix.isBlank()) {
                SuffixNode suffixNode = SuffixNode.builder(suffix, config.getSuffixPriority()).withContext(context).build();
                user.data().add(suffixNode);
            }

            MetaNode slugNode = MetaNode.builder("voidrp:nation_slug", nation.slug()).withContext(context).build();
            MetaNode roleNode = MetaNode.builder("voidrp:nation_role", role).withContext(context).build();

            user.data().add(slugNode);
            user.data().add(roleNode);

            dataStore.setNationMeta(player.getUniqueId(), prefix, suffix, nation.slug(), role);
            dataStore.saveNow();
            luckPerms.getUserManager().saveUser(user);
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to apply nation meta for " + player.getName() + ": " + exception.getMessage());
        }
    }

    private void removePreviousManagedMeta(User user, UUID playerId) {
        String previousPrefix = dataStore.getNationMetaPrefix(playerId);
        String previousSuffix = dataStore.getNationMetaSuffix(playerId);
        ImmutableContextSet context = buildContext();

        if (previousPrefix != null && !previousPrefix.isBlank()) {
            PrefixNode oldPrefix = PrefixNode.builder(previousPrefix, config.getPrefixPriority()).withContext(context).build();
            user.data().remove(oldPrefix);
        }

        if (previousSuffix != null && !previousSuffix.isBlank()) {
            SuffixNode oldSuffix = SuffixNode.builder(previousSuffix, config.getSuffixPriority()).withContext(context).build();
            user.data().remove(oldSuffix);
        }

        user.data().toCollection().stream()
            .filter(node -> node.getType() == NodeType.META)
            .map(NodeType.META::cast)
            .filter(node -> node.getMetaKey().equals("voidrp:nation_slug") || node.getMetaKey().equals("voidrp:nation_role"))
            .toList()
            .forEach(user.data()::remove);
    }

    private String buildCustomPrefix(NationDefinition nation, String role, String customPrefix) {
        String accentCode = hexToBungeeColor(nation.accentColor());
        String coloredText = accentCode + customPrefix;
        String raw = config.getPrefixTemplate()
            .replace("%tag%", coloredText)
            .replace("%title%", coloredText)
            .replace("%slug%", safe(nation.slug()))
            .replace("%role%", safe(config.roleDisplay(role)));
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    private String hexToBungeeColor(String hex) {
        if (hex == null || hex.isBlank()) return "";
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        if (h.length() != 6) return "";
        StringBuilder sb = new StringBuilder("&x");
        for (char c : h.toCharArray()) sb.append('&').append(c);
        return sb.toString();
    }

    private String buildPrefix(NationDefinition nation, String role) {
        String raw = config.getPrefixTemplate()
            .replace("%tag%", safe(nation.tag()))
            .replace("%title%", safe(nation.title()))
            .replace("%slug%", safe(nation.slug()))
            .replace("%role%", safe(config.roleDisplay(role)));
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    private String buildSuffix(NationDefinition nation, String role) {
        String raw = config.getSuffixTemplate()
            .replace("%tag%", safe(nation.tag()))
            .replace("%title%", safe(nation.title()))
            .replace("%slug%", safe(nation.slug()))
            .replace("%role%", safe(config.roleDisplay(role)));
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    private String safe(String value) { return value == null ? "" : value; }

    private ImmutableContextSet buildContext() {
        String serverContext = config.getLuckPermsServerContext();
        if (serverContext == null || serverContext.isBlank()) {
            return ImmutableContextSet.empty();
        }
        return ImmutableContextSet.builder().add("server", serverContext).build();
    }

    private LuckPerms resolveLuckPerms() {
        if (!config.isLuckPermsEnabled()) return null;
        if (plugin.getServer().getPluginManager().getPlugin("LuckPerms") == null) {
            plugin.getLogger().warning("LuckPerms not found. Nation prefix/suffix layer disabled.");
            return null;
        }
        try {
            return LuckPermsProvider.get();
        } catch (IllegalStateException exception) {
            plugin.getLogger().warning("LuckPerms API is not ready yet: " + exception.getMessage());
            return null;
        }
    }
}
