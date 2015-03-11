package de.bwaldvogel.mongo.backend;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import org.bson.BSONObject;
import org.bson.BasicBSONObject;
import org.bson.types.BSONTimestamp;
import org.bson.types.ObjectId;

import de.bwaldvogel.mongo.MongoCollection;
import de.bwaldvogel.mongo.exception.MongoServerError;
import de.bwaldvogel.mongo.exception.MongoServerException;

public abstract class AbstractMongoCollection<KEY> implements MongoCollection<KEY> {

    private String collectionName;
    private String databaseName;
    private List<Index<KEY>> indexes = new ArrayList<Index<KEY>>();
    private QueryMatcher matcher = new DefaultQueryMatcher();
    protected final String idField;

    protected AbstractMongoCollection(String databaseName, String collectionName, String idField) {
        this.databaseName = databaseName;
        this.collectionName = collectionName;
        this.idField = idField;
    }

    protected boolean documentMatchesQuery(BSONObject document, BSONObject query) throws MongoServerException {
        return matcher.matches(document, query);
    }

    protected Iterable<BSONObject> queryDocuments(BSONObject query, BSONObject orderBy, int numberToSkip, int numberToReturn) throws MongoServerException {
        synchronized (indexes) {
            for (Index<KEY> index : indexes) {
                if (index.canHandle(query)) {
                    Iterable<KEY> keys = index.getKeys(query);
                    return matchDocuments(query, keys, orderBy, numberToSkip, numberToReturn);
                }
            }
        }

        return matchDocuments(query, orderBy, numberToSkip, numberToReturn);
    }

    protected abstract Iterable<BSONObject> matchDocuments(BSONObject query, BSONObject orderBy, int numberToSkip, int numberToReturn) throws MongoServerException;

    protected abstract Iterable<BSONObject> matchDocuments(BSONObject query, Iterable<KEY> keys, BSONObject orderBy, int numberToSkip, int numberToReturn) throws MongoServerException;

    protected abstract BSONObject getDocument(KEY key);

    protected abstract void updateDataSize(long sizeDelta);

    protected abstract long getDataSize();

    protected abstract KEY addDocumentInternal(BSONObject document);

    @Override
    public synchronized void addDocument(BSONObject document) throws MongoServerException {

        for (Index<KEY> index : indexes) {
            index.checkAdd(document);
        }

        KEY pos = addDocumentInternal(document);

        for (Index<KEY> index : indexes) {
            index.add(document, pos);
        }

        updateDataSize(Utils.calculateSize(document));
    }

    @Override
    public String getFullName() {
        return databaseName + "." + getCollectionName();
    }

    @Override
    public String getCollectionName() {
        return collectionName;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + getFullName() + ")";
    }

    @Override
    public void addIndex(Index<KEY> index) {
        indexes.add(index);
    }

    private void assertNotKeyField(String key) throws MongoServerError {
        if (key.equals(idField)) {
            throw new MongoServerError(10148, "Mod on " + idField + " not allowed");
        }
    }

    private void changeSubdocumentValue(Object document, String key, Object newValue, Integer matchPos)
            throws MongoServerException {
        changeSubdocumentValue(document, key, newValue, new AtomicReference<Integer>(matchPos));
    }

    void changeSubdocumentValue(Object document, String key, Object newValue, AtomicReference<Integer> matchPos)
            throws MongoServerException {
        int dotPos = key.indexOf('.');
        if (dotPos > 0) {
            String mainKey = key.substring(0, dotPos);
            String subKey = getSubkey(key, dotPos, matchPos);

            Object subObject = Utils.getFieldValueListSafe(document, mainKey);
            if (subObject instanceof BSONObject || subObject instanceof List<?>) {
                changeSubdocumentValue(subObject, subKey, newValue, matchPos);
            } else {
                BSONObject obj = new BasicBSONObject();
                changeSubdocumentValue(obj, subKey, newValue, matchPos);
                Utils.setListSafe(document, mainKey, obj);
            }
        } else {
            Utils.setListSafe(document, key, newValue);
        }
    }

    private String getSubkey(String key, int dotPos, AtomicReference<Integer> matchPos) throws MongoServerError {
        String subKey = key.substring(dotPos + 1);

        if (subKey.matches("\\$(\\..+)?")) {
            if (matchPos == null || matchPos.get() == null) {
                throw new MongoServerError(16650, //
                        "Cannot apply the positional operator without a corresponding query " //
                                + "field containing an array.");
            }
            Integer pos = matchPos.getAndSet(null);
            return subKey.replaceFirst("\\$", String.valueOf(pos));
        }
        return subKey;
    }

    private void removeSubdocumentValue(Object document, String key, Integer matchPos) throws MongoServerException {
        removeSubdocumentValue(document, key, new AtomicReference<Integer>(matchPos));
    }

    private void removeSubdocumentValue(Object document, String key, AtomicReference<Integer> matchPos)
            throws MongoServerException {
        int dotPos = key.indexOf('.');
        if (dotPos > 0) {
            String mainKey = key.substring(0, dotPos);
            String subKey = getSubkey(key, dotPos, matchPos);
            Object subObject = Utils.getFieldValueListSafe(document, mainKey);
            if (subObject instanceof BSONObject || subObject instanceof List<?>) {
                removeSubdocumentValue(subObject, subKey, matchPos);
            } else {
                throw new MongoServerException("failed to remove subdocument");
            }
        } else {
            Utils.removeListSafe(document, key);
        }
    }

    private Object getSubdocumentValue(Object document, String key, Integer matchPos) throws MongoServerException {
        return getSubdocumentValue(document, key, new AtomicReference<Integer>(matchPos));
    }

    private Object getSubdocumentValue(Object document, String key, AtomicReference<Integer> matchPos)
            throws MongoServerException {
        int dotPos = key.indexOf('.');
        if (dotPos > 0) {
            String mainKey = key.substring(0, dotPos);
            String subKey = getSubkey(key, dotPos, matchPos);
            Object subObject = Utils.getFieldValueListSafe(document, mainKey);
            if (subObject instanceof BSONObject || subObject instanceof List<?>) {
                return getSubdocumentValue(subObject, subKey, matchPos);
            } else {
                return null;
            }
        } else {
            return Utils.getFieldValueListSafe(document, key);
        }
    }

    private boolean hasSubdocumentValue(Object document, String key)
            throws MongoServerException {
        int dotPos = key.indexOf('.');
        if (dotPos > 0) {
            String mainKey = key.substring(0, dotPos);
            String subKey = getSubkey(key, dotPos, new AtomicReference<Integer>());
            Object subObject = Utils.getFieldValueListSafe(document, mainKey);
            if (subObject instanceof BSONObject || subObject instanceof List<?>) {
                return hasSubdocumentValue(subObject, subKey);
            } else {
                return false;
            }
        } else {
            return Utils.hasFieldValueListSafe(document, key);
        }
    }

    private void modifyField(BSONObject document, String modifier, BSONObject change, Integer matchPos, boolean isUpsert)
            throws MongoServerException {

        final UpdateOperator op;
        try {
            op = UpdateOperator.fromValue(modifier);
        } catch (IllegalArgumentException e) {
            throw new MongoServerError(10147, "Invalid modifier specified: " + modifier);
        }

        if (op != UpdateOperator.UNSET) {
            for (String key : change.keySet()) {
                if (key.startsWith("$")) {
                    throw new MongoServerError(15896, "Modified field name may not start with $");
                }
            }
        }

        switch (op) {
        case SET_ON_INSERT:
            if (!isUpsert) {
                // no upsert → ignore
                return;
            }
            //$FALL-THROUGH$
        case SET:
            for (String key : change.keySet()) {
                Object newValue = change.get(key);
                Object oldValue = getSubdocumentValue(document, key, matchPos);

                if (Utils.nullAwareEquals(newValue, oldValue)) {
                    // no change
                    continue;
                }

                assertNotKeyField(key);

                changeSubdocumentValue(document, key, newValue, matchPos);
            }
            break;

        case UNSET:
            for (String key : change.keySet()) {
                assertNotKeyField(key);
                removeSubdocumentValue(document, key, matchPos);
            }
            break;

        case PUSH:
        case PUSH_ALL:
        case ADD_TO_SET:
            updatePushAllAddToSet(document, op, change, matchPos);
            break;

        case PULL:
        case PULL_ALL:
            for (String key : change.keySet()) {
                Object value = getSubdocumentValue(document, key, matchPos);
                List<Object> list;
                if (value == null) {
                    return;
                } else if (value instanceof List<?>) {
                    list = Utils.asList(value);
                } else {
                    throw new MongoServerError(10142, "Cannot apply " + modifier + " modifier to non-array");
                }

                Object pushValue = change.get(key);
                if (modifier.equals("$pullAll")) {
                    if (!(pushValue instanceof Collection<?>)) {
                        throw new MongoServerError(10153, "Modifier " + modifier + " allowed for arrays only");
                    }
                    @SuppressWarnings("unchecked")
                    Collection<Object> valueList = (Collection<Object>) pushValue;
                    do {
                    } while (list.removeAll(valueList));
                } else {
                    do {
                    } while (list.remove(pushValue));
                }
                // no need to put something back
            }
            break;

        case POP:
            for (String key : change.keySet()) {
                Object value = getSubdocumentValue(document, key, matchPos);
                List<Object> list;
                if (value == null) {
                    return;
                } else if (value instanceof List<?>) {
                    list = Utils.asList(value);
                } else {
                    throw new MongoServerError(10143, "Cannot apply " + modifier + " modifier to non-array");
                }

                Object pushValue = change.get(key);
                if (!list.isEmpty()) {
                    if (pushValue != null && Utils.normalizeValue(pushValue).equals(Double.valueOf(-1.0))) {
                        list.remove(0);
                    } else {
                        list.remove(list.size() - 1);
                    }
                }
                // no need to put something back
            }
            break;

        case INC:
        case MUL:
            for (String key : change.keySet()) {
                assertNotKeyField(key);

                String operation = (op == UpdateOperator.INC) ? "increment" : "multiply";
                Object value = getSubdocumentValue(document, key, matchPos);
                Number number;
                if (value == null) {
                    number = Integer.valueOf(0);
                } else if (value instanceof Number) {
                    number = (Number) value;
                } else {
                    throw new MongoServerException("cannot " + operation + " value '" + value + "'");
                }

                Object changeObject = change.get(key);
                if (!(changeObject instanceof Number)) {
                    throw new MongoServerException("cannot " + operation + " with non-numeric value: " + change);
                }
                Number changeValue = (Number) changeObject;
                final Number newValue;
                if (op == UpdateOperator.INC) {
                    newValue = Utils.addNumbers(number, changeValue);
                } else if (op == UpdateOperator.MUL) {
                    newValue = Utils.multiplyNumbers(number, changeValue);
                } else {
                    throw new RuntimeException();
                }

                changeSubdocumentValue(document, key, newValue, matchPos);
            }
            break;

        case MIN:
        case MAX:
            Comparator<Object> comparator = new ValueComparator();
            for (String key : change.keySet()) {
                assertNotKeyField(key);

                Object newValue = change.get(key);
                Object oldValue = getSubdocumentValue(document, key, matchPos);

                int valueComparison = comparator.compare(newValue, oldValue);

                final boolean shouldChange;
                // If the field does not exists, the $min/$max operator sets the
                // field to the specified value
                if (oldValue == null && !hasSubdocumentValue(document, key)) {
                    shouldChange = true;
                } else if (op == UpdateOperator.MAX) {
                    shouldChange = valueComparison > 0;
                } else if (op == UpdateOperator.MIN) {
                    shouldChange = valueComparison < 0;
                } else {
                    throw new RuntimeException();
                }

                if (shouldChange) {
                    changeSubdocumentValue(document, key, newValue, matchPos);
                }
            }
            break;

        case CURRENT_DATE:
            for (String key : change.keySet()) {
                assertNotKeyField(key);

                Object typeSpecification = change.get(key);

                final boolean useDate;
                if (typeSpecification instanceof Boolean && Utils.isTrue(typeSpecification)) {
                    useDate = true;
                } else if (typeSpecification instanceof BSONObject) {
                    Object type = ((BSONObject) typeSpecification).get("$type");
                    if (type.equals("timestamp")) {
                        useDate = false;
                    } else if (type.equals("date")) {
                        useDate = true;
                    } else {
                        throw new MongoServerError(2,
                                "The '$type' string field is required to be 'date' or 'timestamp': " + change);
                    }
                } else {
                    final String type;
                    if (typeSpecification != null) {
                        type = typeSpecification.getClass().getSimpleName();
                    } else {
                        type = "NULL";
                    }
                    throw new MongoServerError(2, type + " is not a valid type for $currentDate." + //
                            " Please use a boolean ('true') or a $type expression ({$type: 'timestamp/date'})");
                }

                final Object newValue;
                if (useDate) {
                    newValue = new Date();
                } else {
                    int time = (int) (System.currentTimeMillis() / 1000);
                    newValue = new BSONTimestamp(time, 1);
                }

                changeSubdocumentValue(document, key, newValue, matchPos);
            }
            break;

        default:
            throw new MongoServerError(10147, "Invalid modifier specified: " + modifier);
        }
    }

    private void updatePushAllAddToSet(BSONObject document, UpdateOperator updateOperator, BSONObject change, Integer matchPos)
            throws MongoServerException {
        // http://docs.mongodb.org/manual/reference/operator/push/
        for (String key : change.keySet()) {
            Object value = getSubdocumentValue(document, key, matchPos);
            List<Object> list;
            if (value == null) {
                list = new ArrayList<Object>();
            } else if (value instanceof List<?>) {
                list = Utils.asList(value);
            } else {
                throw new MongoServerError(10141, "Cannot apply " + updateOperator + " modifier to non-array");
            }

            Object changeValue = change.get(key);
            if (updateOperator == UpdateOperator.PUSH_ALL) {
                if (!(changeValue instanceof Collection<?>)) {
                    throw new MongoServerError(10153, "Modifier " + updateOperator + " allowed for arrays only");
                }
                @SuppressWarnings("unchecked")
                Collection<Object> valueList = (Collection<Object>) changeValue;
                list.addAll(valueList);
            } else {
                Collection<Object> pushValues = new ArrayList<Object>();
                if (changeValue instanceof BSONObject
                        && ((BSONObject) changeValue).keySet().equals(Collections.singleton("$each"))) {
                    @SuppressWarnings("unchecked")
                    Collection<Object> values = (Collection<Object>) ((BSONObject) changeValue).get("$each");
                    pushValues.addAll(values);
                } else {
                    pushValues.add(changeValue);
                }

                for (Object val : pushValues) {
                    if (updateOperator == UpdateOperator.PUSH) {
                        list.add(val);
                    } else if (updateOperator == UpdateOperator.ADD_TO_SET) {
                        if (!list.contains(val)) {
                            list.add(val);
                        }
                    } else {
                        throw new MongoServerException("internal server error. illegal modifier here: " + updateOperator);
                    }
                }
            }
            changeSubdocumentValue(document, key, list, matchPos);
        }
    }

    private void applyUpdate(BSONObject oldDocument, BSONObject newDocument) throws MongoServerException {

        if (newDocument.equals(oldDocument)) {
            return;
        }

        Object oldId = oldDocument.get(idField);
        Object newId = newDocument.get(idField);

        if (newId != null && !Utils.nullAwareEquals(oldId, newId)) {
            oldId = new BasicBSONObject(idField, oldId);
            newId = new BasicBSONObject(idField, newId);
            throw new MongoServerError(13596, "cannot change _id of a document old:" + oldId + " new:" + newId);
        }

        if (newId == null && oldId != null) {
            newDocument.put(idField, oldId);
        }

        cloneInto(oldDocument, newDocument);
    }

    Object deriveDocumentId(BSONObject selector) {
        Object value = selector.get(idField);
        if (value != null) {
            if (!Utils.containsQueryExpression(value)) {
                return value;
            } else {
                return deriveIdFromExpression(value);
            }
        }
        return new ObjectId();
    }

    private Object deriveIdFromExpression(Object value) {
        BSONObject expression = (BSONObject) value;
        for (String key : expression.keySet()) {
            Object expressionValue = expression.get(key);
            if (key.equals("$in")) {
                Collection<?> list = (Collection<?>) expressionValue;
                if (!list.isEmpty()) {
                    return list.iterator().next();
                }
            }
        }
        // fallback to random object id
        return new ObjectId();
    }

    private BSONObject calculateUpdateDocument(BSONObject oldDocument, BSONObject update, Integer matchPos,
            boolean isUpsert) throws MongoServerException {

        int numStartsWithDollar = 0;
        for (String key : update.keySet()) {
            if (key.startsWith("$")) {
                numStartsWithDollar++;
            }
        }

        BSONObject newDocument = new BasicBSONObject(idField, oldDocument.get(idField));

        if (numStartsWithDollar == update.keySet().size()) {
            cloneInto(newDocument, oldDocument);
            for (String key : update.keySet()) {
                modifyField(newDocument, key, (BSONObject) update.get(key), matchPos, isUpsert);
            }
        } else if (numStartsWithDollar == 0) {
            applyUpdate(newDocument, update);
        } else {
            throw new MongoServerException("illegal update: " + update);
        }

        return newDocument;
    }

    @Override
    public synchronized BSONObject findAndModify(BSONObject query) throws MongoServerException {

        boolean returnNew = Utils.isTrue(query.get("new"));

        if (!query.containsField("remove") && !query.containsField("update")) {
            throw new MongoServerException("need remove or update");
        }

        BSONObject queryObject = new BasicBSONObject();

        if (query.containsField("query")) {
            queryObject.put("query", query.get("query"));
        } else {
            queryObject.put("query", new BasicBSONObject());
        }

        if (query.containsField("sort")) {
            queryObject.put("orderby", query.get("sort"));
        }

        BSONObject lastErrorObject = null;
        BSONObject returnDocument = null;
        int num = 0;
        for (BSONObject document : handleQuery(queryObject, 0, 1)) {
            num++;
            if (Utils.isTrue(query.get("remove"))) {
                removeDocument(document);
                returnDocument = document;
            } else if (query.get("update") != null) {
                BSONObject updateQuery = (BSONObject) query.get("update");

                Integer matchPos = matcher.matchPosition(document, (BSONObject) queryObject.get("query"));

                BSONObject oldDocument = updateDocument(document, updateQuery, matchPos);
                if (returnNew) {
                    returnDocument = document;
                } else {
                    returnDocument = oldDocument;
                }
                lastErrorObject = new BasicBSONObject("updatedExisting", Boolean.TRUE);
                lastErrorObject.put("n", Integer.valueOf(1));
            }
        }
        if (num == 0 && Utils.isTrue(query.get("upsert"))) {
            BSONObject selector = (BSONObject) query.get("query");
            BSONObject updateQuery = (BSONObject) query.get("update");
            BSONObject newDocument = handleUpsert(updateQuery, selector);
            if (returnNew) {
                returnDocument = newDocument;
            } else {
                returnDocument = new BasicBSONObject();
            }
            num++;
        }

        if (query.get("fields") != null) {
            BSONObject fields = (BSONObject) query.get("fields");
            returnDocument = projectDocument(returnDocument, fields, idField);
        }

        BSONObject result = new BasicBSONObject();
        if (lastErrorObject != null) {
            result.put("lastErrorObject", lastErrorObject);
        }
        result.put("value", returnDocument);
        Utils.markOkay(result);
        return result;
    }

    private static BSONObject projectDocument(BSONObject document, BSONObject fields, String idField) {

        if (document == null) {
            return null;
        }

        BSONObject newDocument = new BasicBSONObject();
        for (String key : fields.keySet()) {
            if (Utils.isTrue(fields.get(key))) {
                projectField(document, newDocument, key);
            }
        }

        // implicitly add _id if not mentioned
        // http://docs.mongodb.org/manual/core/read-operations/#result-projections
        if (!fields.containsField(idField)) {
            newDocument.put(idField, document.get(idField));
        }

        return newDocument;
    }

    private static void projectField(BSONObject document, BSONObject newDocument, String key) {

        if (document == null) {
            return;
        }

        int dotPos = key.indexOf('.');
        if (dotPos > 0) {
            String mainKey = key.substring(0, dotPos);
            String subKey = key.substring(dotPos + 1);

            Object object = document.get(mainKey);
            // do not project the subdocument if it is not of type BSONObject
            if (object instanceof BSONObject) {
                if (!newDocument.containsField(mainKey)) {
                    newDocument.put(mainKey, new BasicBSONObject());
                }
                projectField((BSONObject) object, (BSONObject) newDocument.get(mainKey), subKey);
            }
        } else {
            newDocument.put(key, document.get(key));
        }
    }

    public synchronized Iterable<BSONObject> handleQuery(BSONObject queryObject, int numberToSkip, int numberToReturn)
            throws MongoServerException {
        return handleQuery(queryObject, numberToSkip, numberToReturn, null);
    }

    @Override
    public synchronized Iterable<BSONObject> handleQuery(BSONObject queryObject, int numberToSkip, int numberToReturn,
            BSONObject fieldSelector) throws MongoServerException {

        BSONObject query;
        BSONObject orderBy = null;

        if (numberToReturn < 0) {
            // actually: request to close cursor automatically
            numberToReturn = -numberToReturn;
        }

        if (queryObject.containsField("query")) {
            query = (BSONObject) queryObject.get("query");
            orderBy = (BSONObject) queryObject.get("orderby");
        } else if (queryObject.containsField("$query")) {
            query = (BSONObject) queryObject.get("$query");
            orderBy = (BSONObject) queryObject.get("$orderby");
        } else {
            query = queryObject;
        }

        if (count() == 0) {
            return Collections.emptyList();
        }

        Iterable<BSONObject> objs = queryDocuments(query, orderBy, numberToSkip, numberToReturn);

        if (fieldSelector != null && !fieldSelector.keySet().isEmpty()) {
            return new ProjectingIterable(objs, fieldSelector, idField);
        }

        return objs;
    }

    private static class ProjectingIterator implements Iterator<BSONObject> {

        private Iterator<BSONObject> iterator;
        private BSONObject fieldSelector;
        private String idField;

        public ProjectingIterator(Iterator<BSONObject> iterator, BSONObject fieldSelector, String idField) {
            this.iterator = iterator;
            this.fieldSelector = fieldSelector;
            this.idField = idField;
        }

        @Override
        public boolean hasNext() {
           return this.iterator.hasNext();
        }

        @Override
        public BSONObject next() {
            BSONObject document = this.iterator.next();
            BSONObject projectedDocument = projectDocument(document, fieldSelector, idField);
            return projectedDocument;
        }

        @Override
        public void remove() {
           this.iterator.remove();
        }

    }

    private static class ProjectingIterable implements Iterable<BSONObject> {

        private Iterable<BSONObject> iterable;
        private BSONObject fieldSelector;
        private String idField;

        public ProjectingIterable(Iterable<BSONObject> iterable, BSONObject fieldSelector, String idField) {
            this.iterable = iterable;
            this.fieldSelector = fieldSelector;
            this.idField = idField;
        }

        @Override
        public Iterator<BSONObject> iterator() {
            return new ProjectingIterator(iterable.iterator(), fieldSelector, idField);
        }
    }

    @Override
    public synchronized BSONObject handleDistinct(BSONObject query) throws MongoServerException {
        String key = query.get("key").toString();
        BSONObject q = (BSONObject) query.get("query");
        TreeSet<Object> values = new TreeSet<Object>(new ValueComparator());

        for (BSONObject document : queryDocuments(q, null, 0, 0)) {
            if (document.containsField(key)) {
                values.add(document.get(key));
            }
        }

        BSONObject response = new BasicBSONObject("values", new ArrayList<Object>(values));
        Utils.markOkay(response);
        return response;
    }

    @Override
    public synchronized int insertDocuments(List<BSONObject> documents) throws MongoServerException {
        for (BSONObject document : documents) {
            addDocument(document);
        }
        return documents.size();
    }

    @Override
    public synchronized int deleteDocuments(BSONObject selector, int limit) throws MongoServerException {
        int n = 0;
        for (BSONObject document : handleQuery(selector, 0, limit)) {
            if (limit > 0 && n >= limit) {
                throw new MongoServerException("internal error: too many elements (" + n + " >= " + limit + ")");
            }
            removeDocument(document);
            n++;
        }
        return n;
    }

    @Override
    public synchronized BSONObject updateDocuments(BSONObject selector, BSONObject updateQuery, boolean isMulti,
            boolean isUpsert) throws MongoServerException {
        int n = 0;
        boolean updatedExisting = false;

        if (isMulti) {
            for (String key : updateQuery.keySet()) {
                if (!key.startsWith("$")) {
                    throw new MongoServerError(10158, "multi update only works with $ operators");
                }
            }
        }

        for (BSONObject document : queryDocuments(selector, null, 0, 0)) {
            Integer matchPos = matcher.matchPosition(document, selector);
            updateDocument(document, updateQuery, matchPos);
            updatedExisting = true;
            n++;

            if (!isMulti) {
                break;
            }
        }

        BSONObject result = new BasicBSONObject();

        // insert?
        if (n == 0 && isUpsert) {
            BSONObject newDocument = handleUpsert(updateQuery, selector);
            if (!selector.containsField(idField)) {
                result.put("upserted", newDocument.get(idField));
            }
            n++;
        }

        result.put("n", Integer.valueOf(n));
        result.put("updatedExisting", Boolean.valueOf(updatedExisting));
        return result;
    }

    private BSONObject updateDocument(BSONObject document, BSONObject updateQuery, Integer matchPos)
            throws MongoServerException {
        synchronized (document) {
            // copy document
            BSONObject oldDocument = new BasicBSONObject();
            cloneInto(oldDocument, document);

            BSONObject newDocument = calculateUpdateDocument(document, updateQuery, matchPos, false);

            if (!newDocument.equals(oldDocument)) {
                for (Index<KEY> index : indexes) {
                    index.checkUpdate(oldDocument, newDocument);
                }
                for (Index<KEY> index : indexes) {
                    index.updateInPlace(oldDocument, newDocument);
                }

                long oldSize = Utils.calculateSize(oldDocument);
                long newSize = Utils.calculateSize(newDocument);
                updateDataSize(newSize - oldSize);

                // only keep fields that are also in the updated document
                Set<String> fields = new HashSet<String>(document.keySet());
                fields.removeAll(newDocument.keySet());
                for (String key : fields) {
                    document.removeField(key);
                }

                // update the fields
                for (String key : newDocument.keySet()) {
                    if (key.contains(".")) {
                        throw new MongoServerException(
                                "illegal field name. must not happen as it must be catched by the driver");
                    }
                    document.put(key, newDocument.get(key));
                }
            }
            return oldDocument;
        }
    }

    private void cloneInto(BSONObject targetDocument, BSONObject sourceDocument) {
        for (String key : sourceDocument.keySet()) {
            targetDocument.put(key, cloneValue(sourceDocument.get(key)));
        }
    }

    private Object cloneValue(Object value) {
        if (value instanceof BSONObject) {
            BSONObject newValue = new BasicBSONObject();
            cloneInto(newValue, (BSONObject) value);
            return newValue;
        } else if (value instanceof List<?>) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) value;
            List<Object> newValue = new ArrayList<Object>();
            for (Object v : list) {
                newValue.add(cloneValue(v));
            }
            return newValue;
        } else {
            return value;
        }
    }

    private BSONObject handleUpsert(BSONObject updateQuery, BSONObject selector) throws MongoServerException {
        BSONObject document = convertSelectorToDocument(selector);

        BSONObject newDocument = calculateUpdateDocument(document, updateQuery, null, true);
        if (newDocument.get(idField) == null) {
            newDocument.put(idField, deriveDocumentId(selector));
        }
        addDocument(newDocument);
        return newDocument;
    }

    /**
     * convert selector used in an upsert statement into a document
     */
    BSONObject convertSelectorToDocument(BSONObject selector) throws MongoServerException {
        BSONObject document = new BasicBSONObject();
        for (String key : selector.keySet()) {
            if (key.startsWith("$")) {
                continue;
            }

            Object value = selector.get(key);
            if (!Utils.containsQueryExpression(value)) {
                changeSubdocumentValue(document, key, value, (AtomicReference<Integer>) null);
            }
        }
        return document;
    }

    @Override
    public int getNumIndexes() {
        return indexes.size();
    }

    @Override
    public int count(BSONObject query) throws MongoServerException {
        if (query.keySet().isEmpty()) {
            return count();
        }

        int count = 0;
        Iterator<?> it = queryDocuments(query, null, 0, 0).iterator();
        while (it.hasNext()) {
            it.next();
            count++;
        }
        return count;
    }

    @Override
    public BSONObject getStats() {
        long dataSize = getDataSize();

        BSONObject response = new BasicBSONObject("ns", getFullName());
        response.put("count", Integer.valueOf(count()));
        response.put("size", Long.valueOf(dataSize));

        double averageSize = 0;
        if (count() > 0) {
            averageSize = dataSize / (double) count();
        }
        response.put("avgObjSize", Double.valueOf(averageSize));
        response.put("storageSize", Integer.valueOf(0));
        response.put("numExtents", Integer.valueOf(0));
        response.put("nindexes", Integer.valueOf(indexes.size()));
        BSONObject indexSizes = new BasicBSONObject();
        for (Index<KEY> index : indexes) {
            indexSizes.put(index.getName(), Long.valueOf(index.getDataSize()));
        }

        response.put("indexSize", indexSizes);
        Utils.markOkay(response);
        return response;
    }

    @Override
    public synchronized void removeDocument(BSONObject document) throws MongoServerException {
        KEY key = null;

        if (!indexes.isEmpty()) {
            for (Index<KEY> index : indexes) {
                key = index.remove(document);
            }
        } else {
            key = findDocument(document);
        }
        if (key == null) {
            // not found
            return;
        }

        updateDataSize(-Utils.calculateSize(document));

        removeDocumentWithKey(key);
    }

    @Override
    public BSONObject validate() {
        BSONObject response = new BasicBSONObject("ns", getFullName());
        response.put("extentCount", Integer.valueOf(0));
        response.put("datasize", Long.valueOf(getDataSize()));
        response.put("nrecords", Integer.valueOf(getRecordCount()));
        response.put("padding", Integer.valueOf(1));
        response.put("deletedCount", Integer.valueOf(getDeletedCount()));
        response.put("deletedSize", Integer.valueOf(0));

        response.put("nIndexes", Integer.valueOf(indexes.size()));
        BSONObject keysPerIndex = new BasicBSONObject();
        for (Index<KEY> index : indexes) {
            keysPerIndex.put(index.getName(), Long.valueOf(index.getCount()));
        }

        response.put("keysPerIndex", keysPerIndex);
        response.put("valid", Boolean.TRUE);
        response.put("errors", Arrays.asList());
        Utils.markOkay(response);
        return response;
    }

    protected abstract void removeDocumentWithKey(KEY key);

    protected abstract KEY findDocument(BSONObject document);

    protected abstract int getRecordCount();
    protected abstract int getDeletedCount();

}