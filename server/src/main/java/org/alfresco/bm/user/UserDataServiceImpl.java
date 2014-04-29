/*
 * Copyright (C) 2005-2012 Alfresco Software Limited.
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
package org.alfresco.bm.user;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.dao.DuplicateKeyException;

import com.mongodb.BasicDBObjectBuilder;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.MongoException.DuplicateKey;
import com.mongodb.WriteConcern;
import com.mongodb.WriteResult;

/**
 * Service providing access to {@link UserData} storage. All {@link UserData} returned from and persisted
 * with this service will be testrun-specific. The testrun-identifier is set in the constructor.
 *
 * @author Frederik Heremans
 * @author Derek Hulley
 * @author steveglover
 * @since 1.1
 */
public class UserDataServiceImpl extends AbstractUserDataService implements InitializingBean
{
    public static final String FIELD_RANDOMIZER = "randomizer";
    public static final String FIELD_USERNAME = "username";
    public static final String FIELD_PASSWORD = "password";
    public static final String FIELD_CREATED = "created";
    public static final String FIELD_FIRST_NAME = "firstName";
    public static final String FIELD_LAST_NAME = "lastName";
    public static final String FIELD_EMAIL = "email";
    public static final String FIELD_DOMAIN = "domain";
    public static final String FIELD_CLOUD_SIGNUP = "cloudSignUp";
    public static final String FIELD_TICKET = "ticket";
    public static final String FIELD_NODE_ID = "nodeId";

    public static final String FIELD_ID = "id";
    public static final String FIELD_KEY = "key";
    public static final String FIELD_COMPLETE = "complete";

    private DBCollection collection;
    
    public UserDataServiceImpl(DB db, String collection)
    {
        this.collection = db.getCollection(collection);
    }
    
    @Override
    public void afterPropertiesSet() throws Exception
    {
        checkIndexes();
    }

    /**
     * Ensure that the MongoDB collection has the required indexes associated with
     * this user bean.
     */
    private void checkIndexes()
    {
        collection.setWriteConcern(WriteConcern.SAFE);
        
        DBObject uidxUserName = BasicDBObjectBuilder
                .start(FIELD_USERNAME, 1)
                .get();
        collection.ensureIndex(uidxUserName, "uidx_username", true);

        DBObject uidxEmail = BasicDBObjectBuilder
                .start(FIELD_EMAIL, 1)
                .get();
        collection.ensureIndex(uidxEmail, "uidx_email", true);

        DBObject idxDomain = BasicDBObjectBuilder
                .start(FIELD_DOMAIN, 1)
                .get();
        collection.ensureIndex(idxDomain, "idx_domain", false);
        
        DBObject idxCreated = BasicDBObjectBuilder
                .start(FIELD_CREATED, 1)
                .add(FIELD_RANDOMIZER, 2)
                .add(FIELD_DOMAIN, 3)
                .get();
        collection.ensureIndex(idxCreated, "idx_created", false);
        
        DBObject idxCloudSignUp = BasicDBObjectBuilder
                .start(FIELD_CLOUD_SIGNUP, 1)
                .add(FIELD_CREATED, 2)
                .add(FIELD_RANDOMIZER, 3)
                .get();
        collection.ensureIndex(idxCloudSignUp, "idx_cloudsignup", false);
        
        DBObject idxCloudSignUpId = BasicDBObjectBuilder
                .start(FIELD_CLOUD_SIGNUP + "." + FIELD_ID, 1)
                .get();
        collection.ensureIndex(idxCloudSignUpId, "idx_cloudsignup_id", false);
    }
    
    /**
     * Helper to convert a Mongo DBObject into the API consumable object
     * <p/>
     * Note that <tt>null</tt> is handled as a <tt>null</tt> return.
     */
    private UserData fromDBObject(DBObject userDataObj)
    {
        if (userDataObj == null)
        {
            return null;
        }
        
        UserData userData = new UserData();
        userData.setUsername((String) userDataObj.get(FIELD_USERNAME));
        userData.setPassword((String) userDataObj.get(FIELD_PASSWORD));
        userData.setCreated((Boolean) userDataObj.get(FIELD_CREATED));
        userData.setFirstName((String) userDataObj.get(FIELD_FIRST_NAME));
        userData.setLastName((String) userDataObj.get(FIELD_LAST_NAME));
        userData.setEmail((String) userDataObj.get(FIELD_EMAIL));
        userData.setDomain((String) userDataObj.get(FIELD_DOMAIN));
        
        DBObject csDataObj = (DBObject) userDataObj.get(FIELD_CLOUD_SIGNUP);
        if (csDataObj != null)
        {
            CloudSignUpData csData = new CloudSignUpData();
            csData.setComplete((Boolean) csDataObj.get(FIELD_COMPLETE));
            csData.setId((String) csDataObj.get(FIELD_ID));
            csData.setKey((String) csDataObj.get(FIELD_KEY));
            userData.setCloudSignUp(csData);
        }
        // Done
        return userData;
    }
    
    /**
     * Turn a cursor into an array of API-friendly objects
     */
    private List<UserData> fromDBCursor(DBCursor cursor)
    {
        int count = cursor.count();
        try
        {
            List<UserData> userDatas = new ArrayList<UserData>(count);
            while (cursor.hasNext())
            {
                DBObject userDataObj = cursor.next();
                UserData userData = fromDBObject(userDataObj);
                userDatas.add(userData);
            }
            // Done
            return userDatas;
        }
        finally
        {
            cursor.close();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void createNewUser(UserData data)
    {
        BasicDBObjectBuilder insertObjBuilder = BasicDBObjectBuilder.start()
                .add(FIELD_RANDOMIZER, data.getRandomizer())
                .add(FIELD_USERNAME, data.getUsername())
                .add(FIELD_PASSWORD, data.getPassword())
                .add(FIELD_CREATED, data.isCreated())
                .add(FIELD_FIRST_NAME, data.getFirstName())
                .add(FIELD_LAST_NAME, data.getLastName())
                .add(FIELD_EMAIL, data.getEmail())
                .add(FIELD_DOMAIN, data.getDomain());
        if (data.getCloudSignUp() != null)
        {
            CloudSignUpData csData = data.getCloudSignUp();
            insertObjBuilder.push(FIELD_CLOUD_SIGNUP)
                    .add(FIELD_ID, csData.getId())
                    .add(FIELD_KEY, csData.getKey())
                    .add(FIELD_COMPLETE, csData.isComplete())
                    .pop();
        }
        insertObjBuilder
                .add(FIELD_TICKET, data.getTicket())
                .add(FIELD_NODE_ID, data.getNodeId());
        DBObject insertObj = insertObjBuilder.get();
        
        try
        {
            WriteResult result = collection.insert(insertObj);
            if (result.getError() != null)
            {
                throw new RuntimeException(
                        "Failed to insert user data: \n" +
                        "   User Data: " + data + "\n" +
                        "   Result:    " + result);
            }
        }
        catch (DuplicateKey e)
        {
            // Specifically throw the Spring version
            throw new DuplicateKeyException("User already exists: " + data, e);
        }
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public void setUserTicket(String username, String ticket)
    {
        DBObject queryObj = BasicDBObjectBuilder.start()
                .add(FIELD_USERNAME, username)
                .get();
        DBObject updateObj = BasicDBObjectBuilder.start()
                .push("$set")
                    .add(FIELD_TICKET, ticket)
                .pop()
                .get();
        WriteResult result = collection.update(queryObj, updateObj);
        if (result.getError() != null || result.getN() != 1)
        {
            throw new RuntimeException(
                    "Failed to update user ticket: \n" +
                    "   Username: " + username + "\n" +
                    "   Ticket:   " + ticket + "\n" +
                    "   Result:   " + result);
        }
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public void setUserPassword(String username, String password)
    {
        DBObject queryObj = BasicDBObjectBuilder.start()
                .add(FIELD_USERNAME, username)
                .get();
        DBObject updateObj = BasicDBObjectBuilder.start()
                .push("$set")
                    .add(FIELD_PASSWORD, password)
                .pop()
                .get();
        WriteResult result = collection.update(queryObj, updateObj);
        if (result.getError() != null || result.getN() != 1)
        {
            throw new RuntimeException(
                    "Failed to update user ticket: \n" +
                    "   Username: " + username + "\n" +
                    "   Password: " + password + "\n" +
                    "   Result:   " + result);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setUserNodeId(String username, String nodeId)
    {
        DBObject queryObj = BasicDBObjectBuilder.start()
                .add(FIELD_USERNAME, username)
                .get();
        DBObject updateObj = BasicDBObjectBuilder.start()
                .push("$set")
                    .add(FIELD_NODE_ID, nodeId)
                .pop()
                .get();
        WriteResult result = collection.update(queryObj, updateObj);
        if (result.getError() != null || result.getN() != 1)
        {
            throw new RuntimeException(
                    "Failed to update user ticket: \n" +
                    "   Username: " + username + "\n" +
                    "   NodeId:   " + nodeId + "\n" +
                    "   Result:   " + result);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setUserCreated(String username, boolean created)
    {
        DBObject queryObj = BasicDBObjectBuilder.start()
                .add(FIELD_USERNAME, username)
                .get();
        DBObject updateObj = BasicDBObjectBuilder.start()
                .push("$set")
                    .add(FIELD_CREATED, created)
                .pop()
                .get();
        WriteResult result = collection.update(queryObj, updateObj);
        if (result.getError() != null || result.getN() != 1)
        {
            throw new RuntimeException(
                    "Failed to update user ticket: \n" +
                    "   Username: " + username + "\n" +
                    "   Created:  " + created + "\n" +
                    "   Result:   " + result);
        }
    }
    
    /**
     * @param created               <tt>true</tt> to only count users present in Alfresco
     */
    @Override
    public long countUsers(boolean created)
    {
        return countUsers(null, created);
    }
    
    @Override
    public long countUsers(String domain, boolean created)
    {
        BasicDBObjectBuilder queryObjBuilder = BasicDBObjectBuilder.start();
        if (domain != null)
        {
            queryObjBuilder.add(FIELD_DOMAIN, domain);
        }
        if (created)
        {
            queryObjBuilder.add(FIELD_CREATED, true);
        }
        DBObject queryObj = queryObjBuilder.get();
        return collection.count(queryObj);
    }

    /**
     * @return                      a count of all users in any state
     */
    @Override
    public long countUsers()
    {
        return countUsers(null, false);
    }
    
    /**
     * Find a user by username
     * 
     * @return                          the {@link UserData} found otherwise <tt>null</tt.
     */
    @Override
    public UserData findUserByUsername(String username)
    {
        DBObject queryObj = BasicDBObjectBuilder.start()
                .add(FIELD_USERNAME, username)
                .get();
        DBObject userDataObj = collection.findOne(queryObj);
        return fromDBObject(userDataObj);
    }
    
    /**
     * Find a user by email address
     * 
     * @return                          the {@link UserData} found otherwise <tt>null</tt.
     */
    @Override
    public UserData findUserByEmail(String email)
    {
        DBObject queryObj = BasicDBObjectBuilder.start()
                .add(FIELD_EMAIL, email)
                .get();
        DBObject userDataObj = collection.findOne(queryObj);
        return fromDBObject(userDataObj);
    }
    
    /**
     * @param created               <tt>true</tt> to only count users present in Alfresco
     */
    protected List<UserData> getUsers(boolean created, int startIndex, int count)
    {
        DBObject queryObj = BasicDBObjectBuilder.start()
                .add(FIELD_CREATED, created)
                .get();
        DBCursor cursor = collection.find(queryObj).skip(startIndex).limit(count);
        return fromDBCursor(cursor);
    }

    /**
     * @return              the maximum value of the randomizer
     */
    private int getMaxRandomizer()
    {
        DBObject queryObj = BasicDBObjectBuilder.start()
                .add(FIELD_CREATED, Boolean.TRUE)
                .get();
        DBObject sortObj = BasicDBObjectBuilder.start()
                .add(FIELD_RANDOMIZER, -1)
                .get();
        DBObject fieldsObj = BasicDBObjectBuilder.start()
                .add(FIELD_RANDOMIZER, Boolean.TRUE)
                .get();
        
        DBObject resultObj = collection.findOne(queryObj, fieldsObj, sortObj);
        if (resultObj == null)
        {
            return 0;
        }
        else
        {
            int randomizer = (Integer) resultObj.get(FIELD_RANDOMIZER);
            return randomizer;
        }
    }

    @Override
    public UserData getRandomUser()
    {
        int upper = getMaxRandomizer();             // The upper limit will be exclusive
        int random = (int) (Math.random() * (double) upper);
        
        DBObject queryObj = BasicDBObjectBuilder.start()
                .add(FIELD_CREATED, Boolean.TRUE)
                .push(FIELD_RANDOMIZER)
                    .add("$gte", Integer.valueOf(random))
                .pop()
                .get();
        
        DBObject userDataObj = collection.findOne(queryObj);
        return fromDBObject(userDataObj);
    }
    
    /*
     * CLOUD USER SERVICES
     */

    /**
     * Set the registration data for a user
     * 
     * @param username                  the username
     * @param cloudSignUp               the new registration data to set
     */
    @Override
    public void setUserCloudSignUp(String username, CloudSignUpData cloudSignUp)
    {
        DBObject queryObj = BasicDBObjectBuilder.start()
                .add(FIELD_USERNAME, username)
                .get();
        DBObject updateObj = BasicDBObjectBuilder.start()
                .push("$set")
                    .push(FIELD_CLOUD_SIGNUP)
                        .add(FIELD_ID, cloudSignUp.getId())
                        .add(FIELD_KEY, cloudSignUp.getKey())
                        .add(FIELD_COMPLETE, cloudSignUp.isComplete())
                    .pop()
                .pop()
                .get();
        
        WriteResult result = collection.update(queryObj, updateObj);
        if (result.getError() != null || result.getN() != 1)
        {
            throw new RuntimeException(
                    "Failed to update user cloud signup data: \n" +
                    "   User:    " + username + "\n" +
                    "   Data:    " + cloudSignUp + "\n" +
                    "   Result:  " + result);
        }
    }
    
    /**
     * Count the number of cloud-enabled users, regardless of signup state
     * 
     * @return              the number of users that have cloud registration details, regardless of state
     */
    @Override
    public long countCloudAwareUsers()
    {
        DBObject queryObj = BasicDBObjectBuilder.start()
                .push(FIELD_CLOUD_SIGNUP)
                    .add("$exists", Boolean.TRUE)
                .pop()
                .get();
        return collection.count(queryObj);
    }
    
    /**
     * Retrieves a selection of users that have no cloud signup details.  Note they must also
     * not be created in any instance of Alfresco.
     */
    @Override
    public List<UserData> getUsersWithoutCloudSignUp(int startIndex, int count)
    {
        DBObject queryObj = BasicDBObjectBuilder.start()
                .push(FIELD_CLOUD_SIGNUP)
                    .add("$exists", Boolean.FALSE)
                .pop()
                .add(FIELD_CREATED, Boolean.FALSE)
                .get();
        DBCursor cursor = collection.find(queryObj).skip(startIndex).limit(count);
        
        return fromDBCursor(cursor);
    }
    
    /*
     * USER DOMAIN SERVICES
     */

    @Override
    public List<UserData> getUsersInDomain(String domain, int startIndex, int count)
    {
        DBObject queryObj = BasicDBObjectBuilder.start()
                .add(FIELD_DOMAIN, domain)
                .get();
        DBCursor cursor = collection.find(queryObj).skip(startIndex).limit(count);
        
        return fromDBCursor(cursor);
    }
    
    @Override
    public List<UserData> getUsersInDomain(String domain, int startIndex, int count, boolean created)
    {
        DBObject queryObj = BasicDBObjectBuilder.start()
                .add(FIELD_DOMAIN, domain)
                .add(FIELD_CREATED, created)
                .get();
        DBCursor cursor = collection.find(queryObj).skip(startIndex).limit(count);
        
        return fromDBCursor(cursor);
    }
    
    @Override
    public Iterator<String> getDomainsIterator()
    {
        @SuppressWarnings("unchecked")
        List<String> domains = (List<String>) collection.distinct(FIELD_DOMAIN);
        return domains.iterator();
    }

    @Override
    public UserData getRandomUserFromDomain(String domain)
    {
        List<String> domains = Collections.singletonList(domain);
        return getRandomUserFromDomains(domains);
    }
    
    private Range getRandomizerRange(List<String> domains)
    {
        BasicDBObjectBuilder queryObjBuilder = BasicDBObjectBuilder.start()
                .add(FIELD_CREATED, Boolean.TRUE);
        if (domains.size() > 0)
        {
            queryObjBuilder
                .push(FIELD_DOMAIN)
                    .add("$in", domains)
                .pop();
        }
        DBObject queryObj = queryObjBuilder.get();

        DBObject fieldsObj = BasicDBObjectBuilder.start()
                .add(FIELD_RANDOMIZER, Boolean.TRUE)
                .get();
        
        DBObject sortObj = BasicDBObjectBuilder.start()
                .add(FIELD_RANDOMIZER, -1)
                .get();
        
        // Find max
        DBObject resultObj = collection.findOne(queryObj, fieldsObj, sortObj);
        int maxRandomizer = resultObj == null ? 0 : (Integer) resultObj.get(FIELD_RANDOMIZER);
        
        // Find min
        sortObj.put(FIELD_RANDOMIZER, +1);
        resultObj = collection.findOne(queryObj, fieldsObj, sortObj);
        int minRandomizer = resultObj == null ? 0 : (Integer) resultObj.get(FIELD_RANDOMIZER);
        
        return new Range(minRandomizer, maxRandomizer);
    }
    
    @Override
    public UserData getRandomUserFromDomains(List<String> domains)
    {
        Range range = getRandomizerRange(domains);
        int upper = range.getMax();
        int lower = range.getMin();
        int random = lower + (int) (Math.random() * (double) (upper - lower));

        BasicDBObjectBuilder queryObjBuilder = BasicDBObjectBuilder.start()
                .add(FIELD_CREATED, Boolean.TRUE)
                .push(FIELD_RANDOMIZER)
                    .add("$gte", random)
                .pop();
        if (domains.size() > 0)
        {
            queryObjBuilder
                .push(FIELD_DOMAIN)
                    .add("$in", domains)
                .pop();
        }
        DBObject queryObj = queryObjBuilder.get();
        
        DBObject userDataObj = collection.findOne(queryObj);
        return fromDBObject(userDataObj);
    }
    
    @Override
    public Iterator<UserData> getUsersByDomainIterator(String domain)
    {
        DBObject queryObj = BasicDBObjectBuilder.start()
                .add(FIELD_DOMAIN, domain)
                .add(FIELD_CREATED, Boolean.TRUE)
                .get();
        DBCursor cursor = collection.find(queryObj);
        
        return fromDBCursor(cursor).iterator();
    }
    
    public static class Range
    {
        private int min;
        private int max;
        
        public Range(int min, int max)
        {
            super();
            this.min = min;
            this.max = max;
        }

        public int getMin()
        {
            return min;
        }

        public int getMax()
        {
            return max;
        }
    }
}
