/*
 * Copyright (C) 2005-2013 Alfresco Software Limited.
 *
 * This file is part of Alfresco
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 */
package org.alfresco.bm.api.v1;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response.Status;

import org.alfresco.bm.api.AbstractRestResource;
import org.alfresco.bm.test.TestRunState;
import org.alfresco.bm.test.TestService;
import org.alfresco.bm.test.TestService.ConcurrencyException;
import org.alfresco.bm.test.TestService.NotFoundException;
import org.alfresco.bm.test.TestService.RunStateException;
import org.alfresco.bm.test.mongo.MongoTestDAO;

import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.MongoException.DuplicateKey;
import com.mongodb.util.JSON;

/**
 * <b>REST API V1</b><br/>
 * <p>
 * The url pattern:
 *     <ul>
 *         <li>&lt;API URL&gt;/v1/tests</pre></li>
 *     </ul>
 * </p>
 * Delegate the request to service layer and responds with json.
 * 
 * @author Michael Suzuki
 * @author Derek Hulley
 * @since 2.0
 */
@Path("/v1/tests")
public class TestRestAPI extends AbstractRestResource
{
    private final MongoTestDAO testDAO;
    private final TestService testService;
    
    /**
     * @param testDAO                   low-level data service for tests
     * @param testService               test service for retrieving calculated data
     */
    public TestRestAPI(MongoTestDAO testDAO, TestService testService)
    {
        this.testDAO = testDAO;
        this.testService = testService;
    }
    
    @GET
    @Path("/")
    @Produces(MediaType.APPLICATION_JSON)
    public String getTests(
            @QueryParam("release") String release,
            @QueryParam("schema") Integer schema,
            @DefaultValue("0") @QueryParam("skip") int skip,
            @DefaultValue("50") @QueryParam("count") int count
            )
    {
        if (logger.isDebugEnabled())
        {
            logger.debug(
                    "Inbound: " +
                    "[release:" + release +
                    ",schema:" + schema +
                    ",skip:" + skip +
                    ",count:" + count +
                    "]");
        }
        try
        {
            String json = "[]";
            DBCursor cursor = testDAO.getTests(release, schema, skip, count);
            if (cursor.count() > 0)
            {
                json = JSON.serialize(cursor);
            }
            if (logger.isDebugEnabled())
            {
                logger.debug("Outbound: " + json);
            }
            return json;
        }
        catch (WebApplicationException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throwAndLogException(Status.INTERNAL_SERVER_ERROR, e);
            return null;
        }
    }

    /**
     * Retired for a *POST* to '/'
     */
    @POST
    @Path("/create")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Deprecated
    public String createTestOld(TestDetails testDetails)
    {
        return createTest(testDetails);
    }

    @POST
    @Path("/")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public String createTest(TestDetails testDetails)
    {
        String name = testDetails.getName();
        String description = testDetails.getDescription();
        String release = testDetails.getRelease();
        Integer schema = testDetails.getSchema();
        // When copying
        String copyOf = testDetails.getCopyOf();
        int version = testDetails.getVersion();
        
        if (logger.isDebugEnabled())
        {
            logger.debug(
                    "Inbound: " +
                    "[name:" + name +
                    ",release:" + release +
                    ",schema:" + schema +
                    ",description:" + description +
                    ",copyOf:" + copyOf +
                    ",version" + version +
                    "]");
        }
        
        // Check the name of the test
        if (name == null || name.length() == 0)
        {
            throwAndLogException(Status.BAD_REQUEST, "No test name supplied.");
            return null;
        }
        
        Pattern pattern = Pattern.compile(TEST_NAME_REGEX);
        Matcher matcher = pattern.matcher(name);
        if (!matcher.matches())
        {
            throwAndLogException(
                    Status.BAD_REQUEST,
                    "The test name '" + name + "' is invalid.  " +
                    "Test names must start with a letter and contain only letters, numbers or underscores e.g 'TEST_01'.");
        }

        // Checks specific to copying or creating anew
        boolean copy = copyOf != null;
        if (!copy)
        {
            // Get the definition and make sure that we have details to play with
            // Note that it will throw an exception if the defintion does not exist
            DBObject testDefObj = testDAO.getTestDef(release, schema);
            if (testDefObj == null)
            {
                throwAndLogException(Status.NOT_FOUND, "Test definition not found for " + release + " schema " + schema + ".");
                return null;
            }
        }
        
        try
        {
            boolean written = false;
            if (copy)
            {
                // This is a copy
                written = testDAO.copyTest(name, copyOf, version);
                if (!written)
                {
                    DBObject copyOfObj = testDAO.getTest(copyOf, false);
                    Integer copyOfVersion = copyOfObj == null ? null : (Integer) copyOfObj.get(FIELD_VERSION);
                    if (copyOfVersion != null && copyOfVersion.equals(version))
                    {
                        throwAndLogException(
                                Status.CONFLICT,
                                "A test with name '" + name + "' already exists.");
                    }
                    else
                    {
                        throwAndLogException(
                                Status.NOT_FOUND,
                                "The test to copy was not found: " + copyOf + "(V" + version + ")");
                    }
                }
            }
            else
            {
                // This is a create
                written = testDAO.createTest(name, description, release, schema);
                if (!written)
                {
                    throwAndLogException(Status.CONFLICT, "A test with name '" + name + "' already exists.");
                }
            }
            // Now fetch the full test definition
            DBObject dbObject = testDAO.getTest(name, true);
            if (dbObject == null)
            {
                throwAndLogException(Status.NOT_FOUND, "The newly create test '" + name + "' could not be found.");
                return null;
            }
            dbObject = maskValues(dbObject);
            String json = JSON.serialize(dbObject);
            if (logger.isDebugEnabled())
            {
                logger.debug("Outbound: " + json);
            }
            return json;
        }
        catch (WebApplicationException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throwAndLogException(Status.INTERNAL_SERVER_ERROR, e);
            return null;
        }
    }

    @GET
    @Path("/{test}")
    @Produces(MediaType.APPLICATION_JSON)
    public String getTest(@PathParam("test") String test)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug(
                    "Inbound: " +
                    "[test:" + test +
                    "]");
        }
        try
        {
            DBObject dbObject = testDAO.getTest(test, true);
            if (dbObject == null)
            {
                throwAndLogException(Status.NOT_FOUND, "The test '" + test + "' does not exist.");
            }
            dbObject = maskValues(dbObject);
            String json = JSON.serialize(dbObject);
            if (logger.isDebugEnabled())
            {
                logger.debug("Outbound: " + json);
            }
            return json;
        }
        catch (WebApplicationException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throwAndLogException(Status.INTERNAL_SERVER_ERROR, e);
            return null;
        }
    }
    
    @PUT
    @Path("/")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public String updateTest(TestDetails testDetails)
    {
        String name = testDetails.getName();
        String oldName = testDetails.getOldName();
        Integer version = testDetails.getVersion();
        String description = testDetails.getDescription();
        String release = testDetails.getRelease();
        Integer schema = testDetails.getSchema();
        
        if (logger.isDebugEnabled())
        {
            logger.debug(
                    "Inbound: " +
                    "[name:" + name +
                    ",oldName:" + oldName +
                    ",version:" + version +
                    ",release:" + release +
                    ",schema:" + schema +
                    ",name:" + name +
                    ",description:" + description +
                    "]");
        }
        
        // Check if the test definition is going to change
        if (release != null || schema != null)
        {
            // Get the definition and make sure that we have details to play with
            DBObject testDef = testDAO.getTestDef(release, schema);
            if (testDef == null)
            {
                throwAndLogException(Status.NOT_FOUND, "Test definition not found for " + release + " schema " + schema + ".");
                return null;
            }
        }
        
        if (oldName == null || version == null)
        {
            throwAndLogException(Status.BAD_REQUEST, "A 'version' must be applied with the 'oldName'");
        }
        
        if (name == null || name.length() == 0)
        {
            throwAndLogException(Status.BAD_REQUEST, "No test name supplied.");
            return null;
        }
        
        // Check the name of the test
        Pattern pattern = Pattern.compile(TEST_NAME_REGEX);
        Matcher matcher = pattern.matcher(name);
        if (!matcher.matches())
        {
            throwAndLogException(
                    Status.BAD_REQUEST,
                    "The test name '" + name + "' is invalid.  " +
                    "Test names must start with a letter and contain only letters, numbers or underscores e.g 'TEST_01'.");
        }
        
        try
        {
            // This is an update
            boolean written = testDAO.updateTest(oldName, version, name, description, release, schema);
            if (!written)
            {
                throwAndLogException(Status.NOT_FOUND, "Could not update test '" + oldName + "'.");
            }
            // Now fetch the full test definition
            DBObject dbObject = testDAO.getTest(name, true);
            if (dbObject == null)
            {
                throwAndLogException(Status.NOT_FOUND, "The updated test '" + name + "' could not be found.");
                return null;
            }
            String json = JSON.serialize(dbObject);
            if (logger.isDebugEnabled())
            {
                logger.debug("Outbound: " + json);
            }
            return json;
        }
        catch (WebApplicationException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throwAndLogException(Status.INTERNAL_SERVER_ERROR, e);
            return null;
        }
    }
    
    @DELETE
    @Path("/{test}")
    public void deleteTest(@PathParam("test") String test)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug(
                    "Inbound: " +
                    "[test:" + test +
                    "]");
        }
        try
        {
            boolean deleted = testDAO.deleteTest(test);
            if (!deleted)
            {
                throwAndLogException(Status.NOT_FOUND, "The test '" + test + "' was not deleted.");
            }
        }
        catch (WebApplicationException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throwAndLogException(Status.INTERNAL_SERVER_ERROR, e);
        }
    }
    
    @GET
    @Path("/{test}/props/{property}")
    @Produces(MediaType.APPLICATION_JSON)
    public String getTestProperty(
            @PathParam("test") String test,
            @PathParam("property") String property)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug(
                    "Inbound: " +
                    "[test:" + test +
                    ", property: " + property +
                    "]");
        }
        String exampleUrl = "/tests/MYTEST/props/MYPROP";
        if (test == null || test.length() < 1)
        {
            throwAndLogException(Status.BAD_REQUEST, "No test name provided: " + exampleUrl);
        }
        if (property == null || property.length() == 0)
        {
            throwAndLogException(Status.BAD_REQUEST, "No test property name supplied: " + exampleUrl);
        }

        try
        {
            DBObject dbObject = testDAO.getProperty(test, null, property);
            if (dbObject == null)
            {
                throwAndLogException(Status.NOT_FOUND, "The property '" + test + "." + property + "' does not exist.");
            }
            dbObject = maskValues(dbObject);
            String json = JSON.serialize(dbObject);
            if (logger.isDebugEnabled())
            {
                logger.debug("Outbound: " + json);
            }
            return json;
        }
        catch (WebApplicationException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throwAndLogException(Status.INTERNAL_SERVER_ERROR, e);
            return null;
        }
    }
    
    @PUT
    @Path("/{test}/props/{property}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public String setTestProperty(
            @PathParam("test") String test,
            @PathParam("property") String property,
            PropSetBean propBean)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug(
                    "Inbound: " +
                    "[test:" + test +
                    ", property: " + property +
                    ", json:" + propBean +
                    "]");
        }
        String exampleUrl = "/tests/MYTEST/props/MYPROP";
        if (test == null || test.length() < 1)
        {
            throwAndLogException(Status.BAD_REQUEST, "No test name provided: " + exampleUrl);
        }
        if (property == null || property.length() == 0)
        {
            throwAndLogException(Status.BAD_REQUEST, "No test property name supplied: " + exampleUrl);
        }
        String exampleJSON = "{\"version\":\"0\", \"value\":\"someNewValue\"}";
        if (propBean == null)
        {
            throwAndLogException(Status.BAD_REQUEST, "No test property JSON provided: " + exampleJSON);
        }
        if (propBean.getVersion() == null)
        {
            throwAndLogException(Status.BAD_REQUEST, "Invalid property version.  Example body JSON: " + exampleJSON);
        }
        
        String value = propBean.getValue();
        Integer version = propBean.getVersion();
        
        boolean written = testDAO.setPropertyOverride(test, null, property, version, value);
        if (!written)
        {
            throwAndLogException(Status.NOT_FOUND, "Property '" + property + "' was not updated.  The version number was not found: " + version);
        }
        
        // Retrieve the property
        String json = getTestProperty(test, property);
        
        // Done
        if (logger.isDebugEnabled())
        {
            logger.debug("Outbound: " + json);
        }
        return json;
    }
    
    @DELETE
    @Path("/{test}/props/{property}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public String unsetTestProperty(
            @PathParam("test") String test,
            @PathParam("property") String property,
            PropSetBean propBean)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug(
                    "Inbound: " +
                    "[test:" + test +
                    ", property: " + property +
                    ", json:" + propBean +
                    "]");
        }
        String exampleUrl = "/tests/MYTEST/props/MYPROP";
        if (test == null || test.length() < 1)
        {
            throwAndLogException(Status.BAD_REQUEST, "No test name provided: " + exampleUrl);
        }
        if (property == null || property.length() == 0)
        {
            throwAndLogException(Status.BAD_REQUEST, "No test property name supplied: " + exampleUrl);
        }
        String exampleJSON = "{\"version\":\"0\"}";
        if (propBean == null)
        {
            throwAndLogException(Status.BAD_REQUEST, "No test property JSON provided: " + exampleJSON);
        }
        if (propBean.getVersion() == null)
        {
            throwAndLogException(Status.BAD_REQUEST, "Invalid property version.  Example body JSON: " + exampleJSON);
        }
        
        Integer version = propBean.getVersion();
        
        boolean written = testDAO.setPropertyOverride(test, null, property, version, null);
        if (!written)
        {
            throwAndLogException(Status.NOT_FOUND, "Property '" + property + "' was not reset.  The version number was not found: " + version);
        }
        
        // Retrieve the property
        String json = getTestProperty(test, property);
        
        // Done
        if (logger.isDebugEnabled())
        {
            logger.debug("Outbound: " + json);
        }
        return json;
    }
    
    @GET
    @Path("/{test}/runs")
    @Produces(MediaType.APPLICATION_JSON)
    public String getTestRuns(
            @PathParam("test") String test,
            @DefaultValue("0") @QueryParam("skip") int skip,
            @DefaultValue("50") @QueryParam("count") int count,
            @DefaultValue("") @QueryParam("state") String stateStr)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug(
                    "Inbound: " +
                    "[test:" + test +
                    ",skip:" + skip +
                    ",count:" + count +
                    ",state:" + stateStr +
                    "]");
        }
        String exampleUrl = "/tests/MYTEST/run";
        if (test == null || test.length() < 1)
        {
            throwAndLogException(Status.BAD_REQUEST, "No test name provided: " + exampleUrl);
        }
        
        // Handle the 'all' test
        if (test.equals("-"))
        {
            test = null;
        }
        
        // Check the state values
        TestRunState[] states = null;
        if (stateStr.length() == 0)
        {
            states = new TestRunState[0];
        }
        else
        {
            try
            {
                states = new TestRunState[] {TestRunState.valueOf(stateStr)};
            }
            catch (IllegalArgumentException e)
            {
                throwAndLogException(Status.BAD_REQUEST, "Test run state '" + stateStr + "' is not a valid value.");
            }
        }

        try
        {
            String json = "[]";
            DBCursor dbCursor = testDAO.getTestRuns(test, skip, count, states);
            if (dbCursor.count() > 0)
            {
                json = JSON.serialize(dbCursor);
            }
            if (logger.isDebugEnabled())
            {
                logger.debug("Outbound: " + json);
            }
            return json;
        }
        catch (WebApplicationException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throwAndLogException(Status.INTERNAL_SERVER_ERROR, e);
            return null;
        }
    }

    @POST
    @Path("/{test}/runs")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public String createTestRun(
            @PathParam("test") String test,
            TestRunDetails testRunDetails)
    {
        String name = testRunDetails.getName();
        String description = testRunDetails.getDescription();
        // When copying
        String copyOf = testRunDetails.getCopyOf();
        int version = testRunDetails.getVersion();
        
        if (logger.isDebugEnabled())
        {
            logger.debug(
                    "Inbound: " +
                    "[test:" + test +
                    ",name:" + name +
                    ",description:" + description +
                    ",copyOf:" + copyOf +
                    ",version" + version +
                    "]");
        }
        
        // Get the definition and make sure that we have details to play with
        // Note that it will throw an exception if the defintion does not exist
        DBObject testObj = testDAO.getTest(test, false);
        if (testObj == null)
        {
            throwAndLogException(Status.NOT_FOUND, "Test not found: " + test + ".");
            return null;
        }
        
        // Check the name of the test run
        if (name == null || name.length() == 0)
        {
            throwAndLogException(Status.BAD_REQUEST, "No run name supplied.");
            return null;
        }
        
        Pattern pattern = Pattern.compile(RUN_NAME_REGEX);
        Matcher matcher = pattern.matcher(name);
        if (!matcher.matches())
        {
            throwAndLogException(
                    Status.BAD_REQUEST,
                    "The run name '" + name + "' is invalid.  " +
                    "Run names may contain only letters, numbers or underscores e.g 'RUN_01'.");
        }
        

        // Checks specific to copying or creating anew
        boolean copy = copyOf != null;
        
        try
        {
            boolean written = false;
            if (copy)
            {
                // This is a copy
                written = testDAO.copyTestRun(test, name, copyOf, version);
                if (!written)
                {
                    DBObject copyOfObj = testDAO.getTestRun(test, copyOf, false);
                    Integer copyOfVersion = copyOfObj == null ? null : (Integer) copyOfObj.get(FIELD_VERSION);
                    if (copyOfVersion != null && copyOfVersion.equals(version))
                    {
                        throwAndLogException(
                                Status.CONFLICT,
                                "A test run with name '" + test + "." + name + "' already exists.");
                    }
                    else
                    {
                        throwAndLogException(
                                Status.NOT_FOUND,
                                "The test run to copy was not found: " + test + "." + copyOf + "(V" + version + ")");
                    }
                }
            }
            else
            {
                // This is a create
                written = testDAO.createTestRun(test, name, description);
                if (!written)
                {
                    throwAndLogException(Status.CONFLICT, "A test run with name '" + test + "." + name + "' already exists.");
                }
            }
            // Now fetch the full run definition
            DBObject dbObject = testDAO.getTestRun(test, name, true);
            if (dbObject == null)
            {
                throwAndLogException(Status.NOT_FOUND, "The newly create run '" + name + "' could not be found.");
                return null;
            }
            dbObject = maskValues(dbObject);
            String json = JSON.serialize(dbObject);
            if (logger.isDebugEnabled())
            {
                logger.debug("Outbound: " + json);
            }
            return json;
        }
        catch (WebApplicationException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throwAndLogException(Status.INTERNAL_SERVER_ERROR, e);
            return null;
        }
    }

    @GET
    @Path("/{test}/runs/{run}")
    @Produces(MediaType.APPLICATION_JSON)
    public String getTestRun(
            @PathParam("test") String test,
            @PathParam("run") String run)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug(
                    "Inbound: " +
                    "[test:" + test +
                    ", run:" + run +
                    "]");
        }
        try
        {
            DBObject dbObject = testDAO.getTestRun(test, run, true);
            if (dbObject == null)
            {
                throwAndLogException(Status.NOT_FOUND, "The test run '" + test + "." + run + "' does not exist.");
            }
            dbObject = maskValues(dbObject);
            String json = JSON.serialize(dbObject);
            if (logger.isDebugEnabled())
            {
                logger.debug("Outbound: " + json);
            }
            return json;
        }
        catch (WebApplicationException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throwAndLogException(Status.INTERNAL_SERVER_ERROR, e);
            return null;
        }
    }
    
    @PUT
    @Path("/{test}/runs")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public String updateTestRun(
            @PathParam("test") String test,
            TestRunDetails testRunDetails)
    {
        String name = testRunDetails.getName();
        String oldName = testRunDetails.getOldName();
        Integer version = testRunDetails.getVersion();
        String description = testRunDetails.getDescription();
        
        if (logger.isDebugEnabled())
        {
            logger.debug(
                    "Inbound: " +
                    "[test:" + test +
                    ",oldName:" + oldName +
                    ",version:" + version +
                    ",name:" + name +
                    ",description:" + description +
                    "]");
        }
        
        if (test == null || test.length() == 0)
        {
            throwAndLogException(Status.BAD_REQUEST, "No test name supplied.");
            return null;
        }
        
        if (version == null)
        {
            throwAndLogException(Status.BAD_REQUEST, "A 'version' must be supplied to update a test run.");
        }
        if (oldName == null)
        {
            throwAndLogException(Status.BAD_REQUEST, "Test run 'oldName' must be supplied.");
        }
        
        // Check the name of the test
        Pattern pattern = Pattern.compile(RUN_NAME_REGEX);
        Matcher matcher = pattern.matcher(name);
        if (!matcher.matches())
        {
            throwAndLogException(
                    Status.BAD_REQUEST,
                    "The test name '" + name + "' is invalid.  " +
                    "Test run names must contain only letters, numbers or underscores e.g 'RUN_01'.");
        }
        
        try
        {
            // This is an update
            boolean written = testDAO.updateTestRun(test, oldName, version, name, description);
            if (!written)
            {
                throwAndLogException(Status.NOT_FOUND, "Could not update test run '" + test + "." + oldName + "'.");
            }
            // Now fetch the full test run
            DBObject dbObject = testDAO.getTestRun(test, name, true);
            if (dbObject == null)
            {
                throwAndLogException(Status.NOT_FOUND, "The test for test run '" + test + "." + name + "' could not be found.");
                return null;
            }
            String json = JSON.serialize(dbObject);
            if (logger.isDebugEnabled())
            {
                logger.debug("Outbound: " + json);
            }
            return json;
        }
        catch (WebApplicationException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throwAndLogException(Status.INTERNAL_SERVER_ERROR, e);
            return null;
        }
    }
    
    @DELETE
    @Path("/{test}/runs/{run}")
    public void deleteTestRun(
            @PathParam("test") String test,
            @PathParam("run") String run
            )
    {
        if (logger.isDebugEnabled())
        {
            logger.debug(
                    "Inbound: " +
                    "[test:" + test +
                    ",run:" + run +
                    "]");
        }
        try
        {
            boolean deleted = testDAO.deleteTestRun(test, run);
            if (!deleted)
            {
                throwAndLogException(Status.NOT_FOUND, "The test run '" + test + "." + run + "' was not deleted.");
            }
        }
        catch (WebApplicationException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throwAndLogException(Status.INTERNAL_SERVER_ERROR, e);
        }
    }
    
    @GET
    @Path("/{test}/runs/{run}/props/{property}")
    @Produces(MediaType.APPLICATION_JSON)
    public String getTestRunProperty(
            @PathParam("test") String test,
            @PathParam("run") String run,
            @PathParam("property") String property)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug(
                    "Inbound: " +
                    "[test:" + test +
                    ", run: " + run +
                    ", property: " + property +
                    "]");
        }
        String exampleUrl = "/tests/MYTEST/runs/RUN01/props/MYPROP";
        if (test == null || test.length() < 1)
        {
            throwAndLogException(Status.BAD_REQUEST, "No test name provided: " + exampleUrl);
        }
        if (run == null || run.length() < 1)
        {
            throwAndLogException(Status.BAD_REQUEST, "No run name provided: " + exampleUrl);
        }
        if (property == null || property.length() == 0)
        {
            throwAndLogException(Status.BAD_REQUEST, "No run property name supplied: " + exampleUrl);
        }

        try
        {
            DBObject dbObject = testDAO.getProperty(test, run, property);
            if (dbObject == null)
            {
                throwAndLogException(Status.NOT_FOUND, "The property '" + test + "." + run + "." + property + "' does not exist.");
            }
            dbObject = maskValues(dbObject);
            String json = JSON.serialize(dbObject);
            if (logger.isDebugEnabled())
            {
                logger.debug("Outbound: " + json);
            }
            return json;
        }
        catch (WebApplicationException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throwAndLogException(Status.INTERNAL_SERVER_ERROR, e);
            return null;
        }
    }
    
    @PUT
    @Path("/{test}/runs/{run}/props/{property}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public String setTestRunProperty(
            @PathParam("test") String test,
            @PathParam("run") String run,
            @PathParam("property") String property,
            PropSetBean propBean)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug(
                    "Inbound: " +
                    "[test:" + test +
                    ", run: " + run +
                    ", property: " + property +
                    ", json:" + propBean +
                    "]");
        }
        String exampleUrl = "/tests/MYTEST/runs/RUN01/props/MYPROP";
        if (test == null || test.length() < 1)
        {
            throwAndLogException(Status.BAD_REQUEST, "No test name provided: " + exampleUrl);
        }
        if (run == null || run.length() < 1)
        {
            throwAndLogException(Status.BAD_REQUEST, "No run name provided: " + exampleUrl);
        }
        if (property == null || property.length() == 0)
        {
            throwAndLogException(Status.BAD_REQUEST, "No test property name supplied: " + exampleUrl);
        }
        String exampleJSON = "{\"version\":\"0\", \"value\":\"someNewValue\"}";
        if (propBean == null)
        {
            throwAndLogException(Status.BAD_REQUEST, "No test property JSON provided: " + exampleJSON);
        }
        if (propBean.getVersion() == null)
        {
            throwAndLogException(Status.BAD_REQUEST, "Invalid property version.  Example body JSON: " + exampleJSON);
        }
        
        String value = propBean.getValue();
        Integer version = propBean.getVersion();
        
        try
        {
            boolean written = testDAO.setPropertyOverride(test, run, property, version, value);
            if (!written)
            {
                throwAndLogException(
                        Status.CONFLICT,
                        "Property '" + property + "' (" + version + ") was not updated for " + test + "." + run);
            }
        }
        catch (DuplicateKey e)
        {
            throwAndLogException(
                    Status.CONFLICT,
                    "Property '" + property + "' (" + version + ") was not updated for " + test + "." + run);
        }
        catch (IllegalStateException e)
        {
            DBObject runObj = testDAO.getTestRun(test, run, false);
            throwAndLogException(
                    Status.FORBIDDEN,
                    "Properties cannot be changed once a test has started: " + runObj);
        }
        
        // Retrieve the property
        String json = getTestRunProperty(test, run, property);
        
        // Done
        if (logger.isDebugEnabled())
        {
            logger.debug("Outbound: " + json);
        }
        return json;
    }
    
    @GET
    @Path("/{test}/runs/{run}/summary")
    @Produces(MediaType.APPLICATION_JSON)
    public String getTestRunSummary(
            @PathParam("test") String test,
            @PathParam("run") String run)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug(
                    "Inbound: " +
                    "[test:" + test +
                    ", run:" + run +
                    "]");
        }
        try
        {
            DBObject dbObject = testDAO.getTestRun(test, run, false);
            if (dbObject == null)
            {
                throwAndLogException(Status.NOT_FOUND, "The test run '" + test + "." + run + "' does not exist.");
            }
            String json = JSON.serialize(dbObject);
            if (logger.isDebugEnabled())
            {
                logger.debug("Outbound: " + json);
            }
            return json;
        }
        catch (WebApplicationException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throwAndLogException(Status.INTERNAL_SERVER_ERROR, e);
            return null;
        }
    }
    
    @GET
    @Path("/{test}/runs/{run}/state")
    @Produces(MediaType.TEXT_PLAIN)
    public String getTestRunState(
            @PathParam("test") String test,
            @PathParam("run") String run)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug(
                    "Inbound: " +
                    "[test:" + test +
                    ", run:" + run +
                    "]");
        }
        try
        {
            TestRunState state = testService.getTestRunState(test, run);
            if (logger.isDebugEnabled())
            {
                logger.debug("Outbound: " + state);
            }
            return state.toString();
        }
        catch (NotFoundException e)
        {
            throwAndLogException(Status.NOT_FOUND, "The test run '" + test + "." + run + "' does not exist.");
            return null;
        }
        catch (WebApplicationException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throwAndLogException(Status.INTERNAL_SERVER_ERROR, e);
            return null;
        }
    }
    
    @POST
    @Path("/{test}/runs/{run}/schedule")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public String scheduleTestRun(
            @PathParam("test") String test,
            @PathParam("run") String run,
            TestRunSchedule schedule)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug(
                    "Inbound: " +
                    "[test:" + test +
                    ", run: " + run +
                    ", json:" + schedule +
                    "]");
        }
        
        int version = schedule.getVersion();
        long scheduled = schedule.getScheduled();

        String exampleUrl = "/tests/MYTEST/runs/RUN01";
        if (test == null || test.length() < 1)
        {
            throwAndLogException(Status.BAD_REQUEST, "No test name provided: " + exampleUrl);
        }
        if (run == null || run.length() < 1)
        {
            throwAndLogException(Status.BAD_REQUEST, "No run name provided: " + exampleUrl);
        }
        String exampleJSON = "{\"version\":\"0\", \"scheduled\":\"0000000\", \"duration\":\"120000\"}";
        if (version < 0)
        {
            throwAndLogException(Status.BAD_REQUEST, "Invalid property 'version'.  Example body JSON: " + exampleJSON);
        }
        if (scheduled < 0)
        {
            throwAndLogException(Status.BAD_REQUEST, "Invalid property 'scheduled'.  Example body JSON: " + exampleJSON);
        }
        
        try
        {
            testService.scheduleTestRun(test, run, version, scheduled);
        }
        catch (ConcurrencyException e)
        {
            throwAndLogException(Status.CONFLICT, "Test run '" + test + "." + run + "' was not updated (" + schedule + ").");
        }
        catch (NotFoundException e)
        {
            throwAndLogException(Status.NOT_FOUND, "Test run '" + test + "." + run + "' was not found");
        }
        catch (RunStateException e)
        {
            throwAndLogException(Status.CONFLICT, e.getMessage());      // Assume the state moved underneath the client
        }
        
        // Retrieve the test run
        String json = getTestRun(test, run);
        
        // Done
        if (logger.isDebugEnabled())
        {
            logger.debug("Outbound: " + json);
        }
        return json;
    }
    
    @POST
    @Path("/{test}/runs/{run}/terminate")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public String terminateTestRun(
            @PathParam("test") String test,
            @PathParam("run") String run)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug(
                    "Inbound: " +
                    "[test:" + test +
                    ", run: " + run +
                    "]");
        }
        
        String exampleUrl = "/tests/MYTEST/runs/RUN01/terminate";
        if (test == null || test.length() < 1)
        {
            throwAndLogException(Status.BAD_REQUEST, "No test name provided: " + exampleUrl);
        }
        if (run == null || run.length() < 1)
        {
            throwAndLogException(Status.BAD_REQUEST, "No run name provided: " + exampleUrl);
        }
        
        try
        {
            testService.terminateTestRun(test, run);
        }
        catch (ConcurrencyException e)
        {
            throwAndLogException(Status.CONFLICT, "Test run '" + test + "." + run + "' was not terminated.");
        }
        catch (NotFoundException e)
        {
            throwAndLogException(Status.NOT_FOUND, "Test run '" + test + "." + run + "' was not found");
        }
        catch (RunStateException e)
        {
            throwAndLogException(Status.CONFLICT, e.getMessage());      // Assume the state moved underneath the client
        }
        
        // Retrieve the test run
        String json = getTestRun(test, run);
        
        // Done
        if (logger.isDebugEnabled())
        {
            logger.debug("Outbound: " + json);
        }
        return json;
    }
}
