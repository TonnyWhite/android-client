package io.split.android.client.utils;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.util.List;

public class YamlParser {

    Yaml yaml;

    public YamlParser() {
        yaml = new Yaml();
    }

    public <T> T parse(String yamlContent) {
        T parsedContent = null;

        try {
            parsedContent = (T) yaml.load(yamlContent);
        } catch (YAMLException ye) {
            Logger.e("Error parsing yaml file: " + ye.getLocalizedMessage());
        } catch (Exception e) {
            Logger.e("Unknown error while parsing yaml file: " + e.getLocalizedMessage());
        }

        return parsedContent;
    }
}