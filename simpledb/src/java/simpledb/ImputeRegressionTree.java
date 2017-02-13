package simpledb;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Random;

import weka.core.Instance;
import weka.core.Instances;
import weka.classifiers.Classifier;
import weka.classifiers.trees.REPTree;

public class ImputeRegressionTree extends Impute {

    private static final long serialVersionUID = 1L;
    private static final long GENERATOR_SEED = 6832L;
    private static final int NUM_IMPUTATION_EPOCHS = 10;

    private Random random;

    private ArrayList<Tuple> buffer;
    private int nextTupleIndex;

    // Keep track of imputed instances as well as the indices that correspond to
    // these. (Will differ from completeFieldsIndices and dropFieldsIndices if
    // this is a partial impute.) Variables with a '2' suffix index into the
    // Instances object.
    private Instances imputedInstances;
    private List<Integer> completeFieldsIndices2;
    private List<Integer> dropFieldsIndices2;

    // Map from indices in the Instances object to indices in the Tuple buffer.
	private HashMap<Integer, Integer> dropFieldsIndicesMap;

    public ImputeRegressionTree(Collection<String> dropFields, DbIterator child) {
        super(dropFields, child);
        initRng();
        buffer = new ArrayList<>();
        nextTupleIndex = 0;

        imputedInstances = null;
        completeFieldsIndices2 = null;
        dropFieldsIndices2 = null;
    }
    
    private void initRng(){
        random = new Random(GENERATOR_SEED);
    }

    @Override
    public void rewind() throws DbException, TransactionAbortedException {
        child.rewind();
        initRng();
        buffer.clear();
        nextTupleIndex = 0;

        imputedInstances = null;
        completeFieldsIndices2 = null;
        dropFieldsIndices2 = null;
    }

    @Override
    protected Tuple fetchNext() throws DbException, TransactionAbortedException {
		// First, we must add all of the child tuples to the buffer.
		while (child.hasNext()){
			buffer.add(child.next());
		}

    	// Do the impute, if we have not already.
        if (imputedInstances == null){
        	doImpute();
        }
        
        // We've imputed values, so we can actually return them now.
        if (nextTupleIndex < buffer.size()){
            Tuple original = buffer.get(nextTupleIndex);
            Instance inst = imputedInstances.get(nextTupleIndex);
            Tuple imputed = new Tuple(original);
            mergeInstanceIntoTuple(inst, imputed);
            nextTupleIndex++;
            
            return imputed;
        } else {
            return null;
        }
    }
    
    /*
     * Merge Instance instance into Tuple tuple. We know the indices in each of
     * these objects that corresponds to the drop fields, or the fields that
     * have missing values and are being imputed. We first find a map between
     * these sets of indices. Then, we update the appropriate fields of the
     * Tuple using the imputed values from the Instance.
     */
    private void mergeInstanceIntoTuple(Instance instance, Tuple tuple) throws DbException {
    	// Merge the tuple and the instance. Only values in dropFields will
    	// change; the other values of the tuple will be unchanged by the
    	// imputation.
    	Iterator<Entry<Integer, Integer>> indexIt = this.dropFieldsIndicesMap.entrySet().iterator();
    	while (indexIt.hasNext()){
    		Entry<Integer, Integer> map = indexIt.next();
    		int k = map.getKey(); // index in Instance
    		int v = map.getValue(); // index in Tuple

    		double value = instance.value(k);

			if (td.getFieldType(v) == Type.INT_TYPE){
				tuple.setField(v, new IntField((int) value));
			} else if (td.getFieldType(v) == Type.DOUBLE_TYPE){
				tuple.setField(v, new DoubleField(value));
			} else {
				throw new DbException("Field type not implemented.");
			}
    	}
	}

	private void doImpute() throws NoSuchElementException, DbException, TransactionAbortedException{
		int n = buffer.size();
		
		// Get set of complete columns and missing columns. The complete columns
		// are just those -- columns without any missing data. We can't assume
		// that the complete columns are the set complement of the dropFields.
		// The missing columns ("dropFields") -- the ones to impute -- are not
		// necessarily the set of all columns that have any missing data due to
		// allowing partial imputation. We start with the set difference of all
		// columns with the drop fields. Then, we remove any columns that have
		// any missing values.
		List<Integer> completeFieldsIndices = new ArrayList<>();
		for (int i=0; i<td.numFields(); i++){
			if (!dropFieldsIndices.contains(i)){
				completeFieldsIndices.add(i);
			}
		}
		// Can we populate the missing bit array above and then re-use that here?
		Iterator<Integer> it = completeFieldsIndices.iterator();
		while (it.hasNext()){
			int i = it.next();
			for (int j=0; j<n; j++){
				if (buffer.get(j).getField(i).isMissing()){
					it.remove();
					break;
				}
			}
		}

		// Populate Instances object
		List<Integer> allFieldsToInclude = new ArrayList<Integer>(completeFieldsIndices);
		allFieldsToInclude.addAll(dropFieldsIndices);
		Instances train = WekaUtil.relationToInstances("", buffer, td, allFieldsToInclude);
		
		// Now, the problem is that if we haven't retained every column, our indices will be off.
		// We'll recreate the lists of indices by comparing the field names. We
		// don't really need to do this exercise for the completeFields because
		// they are now the set completement of the dropFields.
		this.dropFieldsIndices2 = new ArrayList<>();
		this.dropFieldsIndicesMap = new HashMap<Integer, Integer>();
		for (int i : dropFieldsIndices){
			String fieldName = td.getFieldName(i);
			boolean added = false;
			for (int j=0; j<train.numAttributes(); j++){
				if (train.attribute(j).name().equals(fieldName)){
					dropFieldsIndicesMap.put(j, i);
					dropFieldsIndices2.add(j);
					added = true;
					break;
				}
			}
			if (!added){
				throw new RuntimeException("Not added.");
			}
		}

		// Keep track of missing values in dropFields columns. This map takes
		// column indices in the Tuple dropFields and maps them to a set of row
		// indices that have missing values.
		HashMap<Integer, HashSet<Integer>> dropFieldsMissing = new HashMap<Integer, HashSet<Integer>>();
		for (int i : dropFieldsIndices2){
			dropFieldsMissing.put(i, new HashSet<Integer>());
			for (int j=0; j<n; j++){
				if (train.get(j).isMissing(i)){
					dropFieldsMissing.get(i).add(j);
				}
			}
		}

		// Initialize all missing values using random-in-column.
		for (int i : dropFieldsIndices2){
			for (int j=0; j<n; j++){
				if (dropFieldsMissing.get(i).contains(j)){
					int ind = random.nextInt(n);
					int ind0 = ind;
					while (dropFieldsMissing.get(i).contains(ind)){
						ind++;
						if (ind == n)
							ind = 0;
						if (ind == ind0)
							throw new DbException("Couldn't initialize impute: no non-missing values for field.");
					}
					train.get(j).setValue(i, train.get(ind).value(i));
				}
			}
		}

		// Iterate creation of trees for each missing column. This is the
		// meat of the chained-equation regression trees method.
		for (int j=0; j<NUM_IMPUTATION_EPOCHS; j++){
			for (int imputationColumn : dropFieldsIndices2){
				train.setClassIndex(imputationColumn);
			
				// Create and train tree. Note that use of "Class"/"Classifier" everywhere is a
				// misnomer; the REPTree will produce numerical outputs if the "Class"
				// column is numerical.
				Classifier tree = new REPTree();
				try {
					tree.buildClassifier(train);
				} catch (Exception e) {
					e.printStackTrace();
					throw new DbException("Failed to train classifier.");
				}

				// Replace all the originally missing values in imputationColumn with
				// predicted values.
				Iterator<Integer> imputeTuplesIt = dropFieldsMissing.get(imputationColumn).iterator();
				while (imputeTuplesIt.hasNext()){
					int i = imputeTuplesIt.next();
					try {
						double pred = tree.classifyInstance(train.get(i));
						train.get(i).setValue(imputationColumn, pred);
					} catch (Exception e) {
						e.printStackTrace();
						throw new DbException("Failed to classify instance.");
					}
				}
			}
		}
		
		this.imputedInstances = train;
    }

    /*
     * For impute regression tree, we have that
     * - fitting one decision tree is O(nm log n) where m is the number of dependent attributes
     * - fitting one decision tree where we have m_c complete attributes and m_i
     *   dirty attributes (thus subtract one which is being imputed) is O(n (m_c +
     *   m_i - 1) log n)
     * - fitting m_i decision trees is O(m_i n (m_c + m_i - 1) log (n))
     * - fitting m_i decision trees, k times (where k is the number of
     *   imputation epochs) is O(k * m_i * n * (m_c + m_i - 1) log (n))
     * Note that these computations ignore
     * - the cost of initializing the dirty attributes with an "impute random" strategy
     * - the cost of pruning
     * - a more sophisticated cost complexity calculation
     * @see simpledb.Impute#getEstimatedCost(int, int, int)
     */
	@Override
	public double getEstimatedCost(int numDirty, int numComplete, int numTuples) {
		int m_c = numComplete;
		int m_i = numDirty;
		int n = numTuples;
		int k = NUM_IMPUTATION_EPOCHS;
		double T = k * m_i * n * (m_c + m_i - 1) * Math.log(n);
		
		return T;
	}

}
