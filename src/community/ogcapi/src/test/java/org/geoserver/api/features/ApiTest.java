/* (c) 2018 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.api.features;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.servers.Server;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.stream.Collectors;
import org.geoserver.api.NCNameResourceCodec;
import org.geoserver.wfs.WFSInfo;
import org.hamcrest.CoreMatchers;
import org.junit.Ignore;
import org.junit.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

public class ApiTest extends FeaturesTestSupport {

    @Test
    public void testApiJson() throws Exception {
        MockHttpServletResponse response = getAsMockHttpServletResponse("ogc/features/api", 200);
        assertThat(
                response.getContentType(),
                CoreMatchers.startsWith("application/openapi+json;version=3.0"));
        String json = response.getContentAsString();
        LOGGER.log(Level.INFO, json);

        ObjectMapper mapper = Json.mapper();
        OpenAPI api = mapper.readValue(json, OpenAPI.class);
        validateApi(api);
    }

    @Test
    public void testApiHTML() throws Exception {
        MockHttpServletResponse response =
                getAsMockHttpServletResponse("ogc/features/api?f=text/html", 200);
        assertEquals("text/html", response.getContentType());
        String html = response.getContentAsString();
        LOGGER.info(html);

        // check template expansion worked properly
        assertThat(
                html,
                containsString(
                        "<link rel=\"icon\" type=\"image/png\" href=\"http://localhost:8080/geoserver/swagger-ui/favicon-32x32.png\" sizes=\"32x32\" />"));
        assertThat(
                html,
                containsString(
                        "<link rel=\"icon\" type=\"image/png\" href=\"http://localhost:8080/geoserver/swagger-ui/favicon-16x16.png\" sizes=\"16x16\" />"));
        assertThat(
                html,
                containsString(
                        "<script src=\"http://localhost:8080/geoserver/swagger-ui/swagger-ui-bundle.js\">"));
        assertThat(
                html,
                containsString(
                        "<script src=\"http://localhost:8080/geoserver/swagger-ui/swagger-ui-standalone-preset.js\">"));
        assertThat(
                html,
                containsString(
                        "url: \"http://localhost:8080/geoserver/ogc/features/api?f=application%2Fopenapi%2Bjson%3Bversion%3D3.0\""));
    }

    @Test
    public void testApiYaml() throws Exception {
        String yaml = getAsString("ogc/features/api?f=application/x-yaml");
        LOGGER.log(Level.INFO, yaml);

        ObjectMapper mapper = Yaml.mapper();
        OpenAPI api = mapper.readValue(yaml, OpenAPI.class);
        validateApi(api);
    }

    @Test
    public void testYamlAsAcceptsHeader() throws Exception {
        MockHttpServletRequest request = createRequest("ogc/features/api");
        request.setMethod("GET");
        request.setContent(new byte[] {});
        request.addHeader(HttpHeaders.ACCEPT, "foo/bar, application/x-yaml, text/html");
        MockHttpServletResponse response = dispatch(request);
        assertEquals(200, response.getStatus());
        assertThat(response.getContentType(), CoreMatchers.startsWith("application/x-yaml"));
        String yaml = string(new ByteArrayInputStream(response.getContentAsString().getBytes()));

        ObjectMapper mapper = Yaml.mapper();
        OpenAPI api = mapper.readValue(yaml, OpenAPI.class);
        validateApi(api);
    }

    private void validateApi(OpenAPI api) {
        // only one server
        List<Server> servers = api.getServers();
        assertThat(servers, hasSize(1));
        assertThat(
                servers.get(0).getUrl(), equalTo("http://localhost:8080/geoserver/ogc/features"));

        // paths
        Paths paths = api.getPaths();

        // ... landing page
        PathItem landing = paths.get("/");
        assertNotNull(landing);
        assertThat(landing.getGet().getOperationId(), equalTo("getLandingPage"));

        // ... conformance
        PathItem conformance = paths.get("/conformance");
        assertNotNull(conformance);
        assertThat(conformance.getGet().getOperationId(), equalTo("getRequirementsClasses"));

        // ... collections
        PathItem collections = paths.get("/collections");
        assertNotNull(collections);
        assertThat(collections.getGet().getOperationId(), equalTo("describeCollections"));

        // ... collection
        PathItem collection = paths.get("/collections/{collectionId}");
        assertNotNull(collection);
        assertThat(collection.getGet().getOperationId(), equalTo("describeCollection"));

        // ... features
        PathItem items = paths.get("/collections/{collectionId}/items");
        assertNotNull(items);
        Operation itemsGet = items.getGet();
        assertThat(itemsGet.getOperationId(), equalTo("getFeatures"));
        List<Parameter> parameters = itemsGet.getParameters();
        List<String> itemGetParamNames =
                parameters.stream().map(p -> p.get$ref()).collect(Collectors.toList());
        assertThat(
                itemGetParamNames,
                contains(
                        "#/components/parameters/collectionId",
                        "#/components/parameters/limit",
                        "#/components/parameters/bbox",
                        "#/components/parameters/time"));

        // ... feature
        PathItem item = paths.get("/collections/{collectionId}/items/{featureId}");
        assertNotNull(item);
        assertThat(item.getGet().getOperationId(), equalTo("getFeature"));

        // check collectionId parameter
        Map<String, Parameter> params = api.getComponents().getParameters();
        Parameter collectionId = params.get("collectionId");
        List<String> collectionIdValues = collectionId.getSchema().getEnum();
        List<String> expectedCollectionIds =
                getCatalog()
                        .getFeatureTypes()
                        .stream()
                        .map(ft -> NCNameResourceCodec.encode(ft))
                        .collect(Collectors.toList());
        assertThat(collectionIdValues, equalTo(expectedCollectionIds));

        // check the limit parameter
        Parameter limit = params.get("limit");
        Schema limitSchema = limit.getSchema();
        assertEquals(BigDecimal.valueOf(1), limitSchema.getMinimum());
        WFSInfo wfs = getGeoServer().getService(WFSInfo.class);
        assertEquals(wfs.getMaxFeatures(), limitSchema.getMaximum().intValue());
        assertEquals(wfs.getMaxFeatures(), ((Number) limitSchema.getDefault()).intValue());
    }

    @Test
    @Ignore // workspace specific services not working yet
    public void testWorkspaceQualifiedAPI() throws Exception {
        MockHttpServletRequest request = createRequest("cdf/ogc/features/api");
        request.setMethod("GET");
        request.setContent(new byte[] {});
        request.addHeader(HttpHeaders.ACCEPT, "foo/bar, application/x-yaml, text/html");
        MockHttpServletResponse response = dispatch(request);
        assertEquals(200, response.getStatus());
        assertEquals("application/x-yaml", response.getContentType());
        String yaml = string(new ByteArrayInputStream(response.getContentAsString().getBytes()));

        ObjectMapper mapper = Yaml.mapper();
        OpenAPI api = mapper.readValue(yaml, OpenAPI.class);
        Map<String, Parameter> params = api.getComponents().getParameters();
        Parameter collectionId = params.get("collectionId");
        List<String> collectionIdValues = collectionId.getSchema().getEnum();
        List<String> expectedCollectionIds =
                getCatalog()
                        .getFeatureTypesByNamespace(getCatalog().getNamespaceByPrefix("cdf"))
                        .stream()
                        .map(ft -> ft.getName())
                        .collect(Collectors.toList());
        assertThat(collectionIdValues, equalTo(expectedCollectionIds));
    }
}
