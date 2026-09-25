package com.melocet.bspimport.bsp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a map's logic switches on and off while it's played. Source outputs look like
 * "target,Input,param,delay,times" (newer maps separate with ESC); anything that is ever enabled,
 * disabled, toggled, killed or broken is in a state the map picks at run time (random barricades,
 * blocked doors), so a one-shot build shouldn't bake it in.
 */
public final class EntityLogic {

    private static final Set<String> STATE_INPUTS = Set.of("enable", "disable", "toggle", "kill", "killhierarchy", "break",
            "turnon", "turnoff", "enablecollision", "disablecollision", "setsolid", "enabledraw", "disabledraw");
    private static final Pattern SEPARATOR = Pattern.compile("[,\\x1b]");

    private final Set<String> exact = new HashSet<>();
    private final List<String> prefixes = new ArrayList<>();

    public EntityLogic(List<Map<String, String>> entities) {
        for (Map<String, String> e : entities) {
            for (Map.Entry<String, String> kv : e.entrySet()) {
                String value = kv.getValue();
                if (!kv.getKey().startsWith("on") || value.length() < 5) continue;
                String[] parts = SEPARATOR.split(value, -1);
                if (parts.length < 4) continue;
                String input = parts[1].trim().toLowerCase(Locale.ROOT);
                if (!STATE_INPUTS.contains(input)) continue;
                String target = parts[0].trim().toLowerCase(Locale.ROOT);
                if (target.isEmpty() || target.startsWith("!")) continue;
                if (target.endsWith("*")) prefixes.add(target.substring(0, target.length() - 1));
                else exact.add(target);
            }
        }
    }

    /** Whether the map's logic changes this entity's state while playing. */
    public boolean isSwitched(Map<String, String> entity) {
        String name = entity.getOrDefault("targetname", "").toLowerCase(Locale.ROOT);
        if (name.isEmpty()) return false;
        if (exact.contains(name)) return true;
        for (String p : prefixes) if (name.startsWith(p)) return true;
        return false;
    }

    private static int parseInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public int switchedNames() {
        return exact.size() + prefixes.size();
    }

    /** Not there when the round starts: disabled, never solid, or drawn with nothing. */
    public static boolean startsHidden(Map<String, String> e) {
        if ("1".equals(e.get("startdisabled"))) return true;
        if ("1".equals(e.get("solidity")) && "func_brush".equals(e.get("classname"))) return true;
        if ("func_wall_toggle".equals(e.get("classname")) && (parseInt(e.get("spawnflags")) & 1) != 0) return true;
        String rm = e.getOrDefault("rendermode", "0");
        return rm.equals("10") || !rm.equals("0") && "0".equals(e.get("renderamt"));
    }
}
