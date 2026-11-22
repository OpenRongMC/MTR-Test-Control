package org.mtr.testcontrol;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public final class SuiteResult {

    public static final List<String> AERO_NAMESPACES = List.of("aeronautics", "simulated");

    private static volatile SuiteResult last = new SuiteResult("none", List.of());

    public final String kind;
    public final List<String> namespaces;
    public int blocksListed;
    public int itemsListed;
    public int itemsGiven;
    public int itemsGiveFailed;
    public int checked;
    public int placed;
    public int passed;
    public int skipped;
    public int failed;
    public int blockEntitiesInspected;
    public int blockEntityFailures;
    public final List<String> failures = new ArrayList<>();

    public SuiteResult(String kind, List<String> namespaces) {
        this.kind = kind;
        this.namespaces = List.copyOf(namespaces);
    }

    public static SuiteResult last() {
        return last;
    }

    public static void publish(SuiteResult result) {
        last = result;
        TestOperations.setLastSummary(result.summaryLine());
    }

    public boolean ok() {
        return failed == 0 && blockEntityFailures == 0;
    }

    public int commandResult() {
        return ok() ? 1 : 0;
    }

    public String summaryLine() {
        return "Suite " + kind + " [" + String.join(",", namespaces) + "]: blocksListed=" + blocksListed
                + ", itemsListed=" + itemsListed + ", itemsGiven=" + itemsGiven + ", itemsGiveFailed=" + itemsGiveFailed
                + ", checked=" + checked + ", placed=" + placed + ", passed=" + passed + ", skipped=" + skipped
                + ", failed=" + failed + ", blockEntities=" + blockEntitiesInspected + ", beFailed=" + blockEntityFailures
                + (failures.isEmpty() ? "" : ", first failures=" + String.join(", ", failures.subList(0, Math.min(10, failures.size()))));
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("ok", ok());
        json.addProperty("kind", kind);
        JsonArray ns = new JsonArray();
        namespaces.forEach(ns::add);
        json.add("namespaces", ns);
        json.addProperty("blocksListed", blocksListed);
        json.addProperty("itemsListed", itemsListed);
        json.addProperty("itemsGiven", itemsGiven);
        json.addProperty("itemsGiveFailed", itemsGiveFailed);
        json.addProperty("checked", checked);
        json.addProperty("placed", placed);
        json.addProperty("passed", passed);
        json.addProperty("skipped", skipped);
        json.addProperty("failed", failed);
        json.addProperty("blockEntitiesInspected", blockEntitiesInspected);
        json.addProperty("blockEntityFailures", blockEntityFailures);
        json.addProperty("summary", summaryLine());
        JsonArray failureList = new JsonArray();
        failures.stream().limit(50).forEach(failureList::add);
        json.add("failures", failureList);
        return json;
    }
}
