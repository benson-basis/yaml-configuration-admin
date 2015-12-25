/*
* Copyright 2015 Basis Technology Corp.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.basistech.yca;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.felix.utils.collections.DictionaryAsMap;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Dictionary;
import java.util.Hashtable;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * Watch for files in a specified directory, parse them as yaml or json,
 * and inject them into the ConfigurationAdmin service as flattened key-value sets.
 *
 */
@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
public class FlatteningConfigFileManager {
    private static final Logger LOG = LoggerFactory.getLogger(FlatteningConfigFileManager.class);
    private static final String FILENAME_PROPERTY_KEY = FlatteningConfigFileManager.class.getName() + ".filename";
    private Path configurationDirectory;
    private WatchService watchService;
    private WatchKey watchKey;
    private Thread watcherThread;
    private ConfigurationAdmin configurationAdmin;

    private class WatcherThread extends Thread {

        @Override
        public void run() {
            initialInventory();
            watchLoop();
        }
    }

    private void initialInventory() {
        // The watcher only detects _changes_
        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(configurationDirectory)) {
            for (Path path : directoryStream) {
                processAddOrUpdate(path);
            }
        } catch (IOException ex) {
            LOG.error("Error listing initial directory contents for " + configurationDirectory, ex);
        }
    }

    @Activate
    public void activate(ComponentContext context) {
        String pathname = (String) context.getProperties().get("configurationDirectory");
        if (pathname == null) {
            throw new RuntimeException("There is no configurationDirectory parameter in com.basistech.yca.FlatteningConfigFileManager.cfg");
        }

        configurationDirectory = Paths.get(pathname);
        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            LOG.error("Failed to create watch service", e);
            throw new RuntimeException("Failed to create watch service");
        }
        try {
            watchKey = configurationDirectory.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
        } catch (IOException e) {
            LOG.error("Failed to create watch key for " + configurationDirectory.toAbsolutePath(), e);
            throw new RuntimeException("Failed to create watch key");
        }

        watcherThread = new WatcherThread();
        watcherThread.start();
    }


    @Deactivate
    public void deactivate() {
        watcherThread.interrupt();
    }

    private void watchLoop() {
        for (;;) {

            // wait for key to be signaled
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException x) {
                break;
            }

            for (WatchEvent<?> event: key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();

                if (kind == OVERFLOW) {
                    continue;
                }

                @SuppressWarnings("unchecked")
                WatchEvent<Path> ev = (WatchEvent<Path>)event;
                processEvent(ev);
            }

            boolean valid = key.reset();
            if (!valid) {
                break;
            }
        }
        watchKey.cancel();
        try {
            watchService.close();
        } catch (IOException e) {
            LOG.error("Error closing watch service", e);
        }
    }

    private void processEvent(WatchEvent<Path> ev) {
        Path filename = ev.context();

        if (ev.kind() == ENTRY_DELETE) {
            processDelete(filename);
            return;
        }
        processAddOrUpdate(filename);


    }

    private void processAddOrUpdate(Path filename) {
        // create and modify look quite similar.
        Path child = configurationDirectory.resolve(filename);

        // The heck with content type probing, let's do this the simple way.
        int lastDot = filename.toString().lastIndexOf('.');
        if (lastDot == -1) {
            LOG.info("File has no suffix; ignoring: " + child);
            return;
        }

        ObjectMapper mapper;
        String suffix = filename.toString().substring(lastDot + 1);
        if ("json".equals(suffix) || "js".equals(suffix)) {
            mapper = new ObjectMapper();
        } else if ("yaml".equals(suffix) || "yml".equals(suffix)) {
            mapper = new ObjectMapper(new YAMLFactory());
        } else {
            LOG.error("Unsupported file name " + filename.toString());
            return;
        }

        JsonNode content;
        try {
            content = mapper.readTree(child.toFile());
        } catch (IOException e) {
            LOG.error("Failed to read contents of " + child, e);
            return;
        }

        @SuppressWarnings("unchecked")
        Dictionary<String, Object> dict = (Dictionary<String, Object>) JsonNodeFlattener.flatten(content);

        String pid[] = parsePid(filename);
        Configuration config;
        try {
            config = getConfiguration(toConfigKey(filename), pid[0], pid[1]);
        } catch (IOException e) {
            LOG.error("Failed to get configuration for " + formatPid(pid));
            return;
        }

        Dictionary<String, Object> props = config.getProperties();
        Hashtable<String, Object> old = null;
        if (props != null) {
            old = new Hashtable<>(new DictionaryAsMap<>(props));
        }
        if (old != null) {
            old.remove(FILENAME_PROPERTY_KEY);
            old.remove(Constants.SERVICE_PID);
            old.remove(ConfigurationAdmin.SERVICE_FACTORYPID);
        }

        if (!dict.equals(old)) {
            dict.put(FILENAME_PROPERTY_KEY, toConfigKey(filename));
            if (old == null) {
                LOG.info("Creating configuration from " + filename);
            } else {
                LOG.info("Updating configuration from " + filename);
            }
            try {
                config.update(dict);
            } catch (IOException e) {
                LOG.error("Failed to update configuration for " + formatPid(pid));
            }
        }
    }

    private void processDelete(Path filename) {
        String[] pid = parsePid(filename);
        LOG.info("Delete event for " + formatPid(pid));
        Configuration conf;
        try {
            conf = getConfiguration(toConfigKey(filename), pid[0], pid[1]);
        } catch (IOException e) {
            LOG.error("Failed to obtain configuration for " + formatPid(pid), e);
            return;
        }
        try {
            conf.delete();
        } catch (IOException e) {
            LOG.error("Failed to delete configuration for " + formatPid(pid), e);
        }

    }

    private String[] parsePid(Path path) {
        String filename = path.getName(path.getNameCount() - 1).toString();
        String pid = filename.substring(0, filename.lastIndexOf('.'));
        int n = pid.indexOf('-');
        if (n > 0) {
            String factoryPid = pid.substring(n + 1);
            pid = pid.substring(0, n);
            return new String[] {pid, factoryPid };
        } else {
            return new String[] {pid, null };
        }
    }

    private String formatPid(String[] items) {
        if (items[1] == null) {
            return items[0];
        } else {
            return items[0] + "-" + items[1];
        }
    }

    private String toConfigKey(Path p) {
        return p.toAbsolutePath().toUri().toString();
    }

    /* if we want to do writing back out to the file some day via
       config admin events.
    Path fromConfigKey(String key) {
        return Paths.get(URI.create(key));
    }
    */

    Configuration getConfiguration(String fileName, String pid, String factoryPid) throws IOException {
        Configuration oldConfiguration = findExistingConfiguration(fileName);
        if (oldConfiguration != null) {
            return oldConfiguration;
        } else {
            Configuration newConfiguration;
            if (factoryPid != null) {
                newConfiguration = configurationAdmin.createFactoryConfiguration(pid, null);
            } else {
                newConfiguration = configurationAdmin.getConfiguration(pid, null);
            }
            return newConfiguration;
        }
    }

    Configuration findExistingConfiguration(String fileName) throws IOException {
        String filter = "(" + FILENAME_PROPERTY_KEY + "=" + escapeFilterValue(fileName) + ")";
        Configuration[] configurations;
        try {
            configurations = configurationAdmin.listConfigurations(filter);
        } catch (InvalidSyntaxException e) {
            throw new RuntimeException(e);
        }
        if (configurations != null && configurations.length > 0) {
            return configurations[0];
        } else {
            return null;
        }
    }

    private String escapeFilterValue(String s) {
        return s.replaceAll("[(]", "\\\\(").
                replaceAll("[)]", "\\\\)").
                replaceAll("[=]", "\\\\=").
                replaceAll("[\\*]", "\\\\*");
    }

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    public void setConfigurationAdmin(ConfigurationAdmin configurationAdmin) {
        this.configurationAdmin = configurationAdmin;
    }

}
