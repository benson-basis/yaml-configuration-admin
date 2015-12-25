/*
* Copyright 2014 Basis Technology Corp.
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
import com.google.common.io.Resources;
import org.junit.Test;

import java.net.URL;
import java.util.Dictionary;
import java.util.Hashtable;

import static org.junit.Assert.assertEquals;

/**
 *
 */
public class UnflattenTest {
    @Test
    public void unflattenSomething() throws Exception {
        URL testDocUrl = Resources.getResource(FlattenerTest.class, "flatten-test.yaml");
        JsonNode node = new ObjectMapper(new YAMLFactory()).readTree(testDocUrl);
        Dictionary<String, ?> dict = JsonNodeFlattener.flatten(node);
        JsonNode roundTrip = JsonNodeFlattener.unflatten(dict);
        assertEquals(node, roundTrip);
    }

    @Test
    public void objectInArray() throws Exception {
        Hashtable<String, Object> ht = new Hashtable<>();
        ht.put("a[0].cat", "dog");
        ht.put("a[0].dog", "cat");
        JsonNode roundTrip = JsonNodeFlattener.unflatten(ht);
        JsonNode ref = new ObjectMapper(new YAMLFactory()).readTree("a: [ { cat: dog, dog: cat } ]");
        assertEquals(ref, roundTrip);
    }

    @Test
    public void array2() throws Exception {
        Hashtable<String, Object> ht = new Hashtable<>();
        ht.put("a[0][0]", "dog");
        JsonNode roundTrip = JsonNodeFlattener.unflatten(ht);
        JsonNode ref = new ObjectMapper(new YAMLFactory()).readTree("a: [ [ dog ] ]");
        assertEquals(ref, roundTrip);
    }
}
