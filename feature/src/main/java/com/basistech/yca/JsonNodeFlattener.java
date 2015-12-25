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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Convert between {@link JsonNode} and {@code Dictionary<String, Object>}.
 */
public final class JsonNodeFlattener {

    public static final Object NULL = new Object();

    private JsonNodeFlattener() {
        //
    }

    public static Dictionary<String, ?> flatten(JsonNode node) {
        Dictionary<String, Object> map = new Hashtable<>();
        /* We need a depth-first traversal of the node */
        try {
            traverse(node, "", map);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return map;
    }

    private static void traverse(JsonNode node, String pathSoFar, Dictionary<String, Object> map) throws IOException {
        if (!node.isContainerNode()) {
            Object value;
            if (node.isBigDecimal()) {
                value = node.decimalValue();
            } else if (node.isBigInteger()) {
                value = node.bigIntegerValue();
            } else if (node.isBinary()) {
                value = node.binaryValue();
            } else if (node.isBoolean()) {
                value = node.booleanValue();
            } else if (node.isDouble()) {
                value = node.doubleValue();
            } else if (node.isFloat()) {
                value = node.floatValue();
            } else if (node.isInt()) {
                value = node.intValue();
            } else if (node.isLong()) {
                value = node.longValue();
            } else if (node.isNull()) {
                // NOTE: stupid old Hashtable can't store null values.
                value = NULL;
            } else if (node.isShort()) {
                value = node.shortValue();
            } else if (node.isTextual()) {
                value = node.textValue();
            } else {
                throw new RuntimeException("Unanticipated node " + node);
            }
            map.put(pathSoFar, value);
        } else {
            if (node.isArray()) {
                traverseArray(node, pathSoFar, map);
            } else {
                traverseObject(node, pathSoFar, map);
            }
        }
    }

    private static void traverseObject(JsonNode node, String pathSoFar, Dictionary<String, Object> map) throws IOException {
        String separator = "";
        if (pathSoFar.length() > 0) {
            separator = ".";
        }
        Iterator<Map.Entry<String, JsonNode>> fieldIt = node.fields();
        while (fieldIt.hasNext()) {
            Map.Entry<String, JsonNode> entry = fieldIt.next();
            traverse(entry.getValue(), pathSoFar + separator + entry.getKey(), map);
        }
    }

    private static void traverseArray(JsonNode node, String pathSoFar, Dictionary<String, Object> map) throws IOException {
        for (int nodeIndex = 0; nodeIndex < node.size(); nodeIndex++) {
            String path = pathSoFar + "[" + nodeIndex + "]";
            traverse(node.get(nodeIndex), path, map);
        }
    }


    public static JsonNode unflatten(Dictionary<String, ?> config) {
        Enumeration<String> keyEnum = config.keys();
        List<String> keys = new ArrayList<>();
        while (keyEnum.hasMoreElements()) {
            keys.add(keyEnum.nextElement());
        }
        Collections.sort(keys);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        for (String key : keys) {
            Object value = config.get(key);
            addNode(root, key, value);
        }

        return root;
    }

    // this could be optimized by taking advantage of the sorted order.
    private static void addNode(ObjectNode root, String key, Object value) {
        int keyIndex = 0;
        int nodeIndex = 0; // used to add to arrays.
        JsonNode node = root;
        /*
         * If node is an object, then we can either find a fieldName (terminated by . or [) or be at the end.
         * If node is an array, we either find . or [ or be at the end.
         */
        while (true) {
            int dotIndex = key.indexOf('.', keyIndex);
            int arIndex = key.indexOf('[', keyIndex);
            if (dotIndex == -1) {
                dotIndex = Integer.MAX_VALUE;
            }
            if (arIndex == -1) {
                arIndex = Integer.MAX_VALUE;
            }
            boolean noDelim = dotIndex == Integer.MAX_VALUE && arIndex == Integer.MAX_VALUE;
            int nextDelim = Math.min(arIndex, dotIndex);
            if ((node instanceof ArrayNode) && !(noDelim || nextDelim == keyIndex)) {
                throw new RuntimeException("] not followed by '[', '.', or the end of the key");
            }
            if (node instanceof ObjectNode) {
                String fieldName;
                if (noDelim) {
                    fieldName = key.substring(keyIndex);
                    setValue((ObjectNode) node, fieldName, value);
                    return;
                }
                fieldName = key.substring(keyIndex, nextDelim);
                // we are inserting -- something --
                if (key.charAt(nextDelim) == '.') {
                    JsonNode nextNode = node.get(fieldName);
                    if (nextNode == null) {
                        nextNode = ((ObjectNode) node).putObject(fieldName);
                    }
                    node = nextNode;
                    keyIndex = nextDelim + 1;
                } else {
                    JsonNode nextNode = node.get(fieldName);
                    if (nextNode == null) {
                        nextNode = ((ObjectNode) node).putArray(fieldName);
                    }
                    node = nextNode;
                    /* parse the rest of the array reference */
                    int closeSq = key.indexOf(']', nextDelim + 1);
                    nodeIndex = Integer.parseInt(key.substring(nextDelim + 1, closeSq));
                    keyIndex = closeSq + 1;
                }
            } else {
                // last thing we did was make an array node. so we're at the end, ., or another array.
                if (noDelim) {
                    setValue((ArrayNode) node, nodeIndex, value);
                    return;
                }
                if (key.charAt(nextDelim) == '.') {
                    JsonNode nextNode = node.get(nodeIndex);
                    if (nextNode == null) {
                        nextNode = ((ArrayNode) node).insertObject(nodeIndex);
                    }
                    node = nextNode;
                    keyIndex = nextDelim + 1;
                } else {
                    // ][...]
                    JsonNode nextNode = node.get(nodeIndex);
                    if (nextNode == null) {
                        nextNode = ((ArrayNode) node).insertArray(nodeIndex);
                    }
                    node = nextNode;
                    int closeSq = key.indexOf(']', nextDelim + 1);
                    nodeIndex = Integer.parseInt(key.substring(nextDelim + 1, closeSq));
                    keyIndex = closeSq + 1;
                }
            }
        }
    }

    // these two need identical tests of the type of value, but they need to use them to make
    // different calls to the node. So we seem to need to duplicate the code.
    private static void setValue(ObjectNode node, String fieldName, Object value) {
        if (value == NULL) {
            node.putNull(fieldName);
            return;
        }
        /* the following is slow but compact to write. I'm not in a hurry. */
        try {
            Method suitableSetter = ObjectNode.class.getMethod("put", String.class, value.getClass());
            suitableSetter.invoke(node, fieldName, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setValue(ArrayNode node, int index, Object value) {
        if (value == NULL) {
            node.insertNull(index);
            return;
        }
        try {
        /* the following is slow but compact to write. I'm not in a hurry. */
            Method suitableSetter = ArrayNode.class.getMethod("insert", int.class, value.getClass());
            suitableSetter.invoke(node, index, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
