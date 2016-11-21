package simpledb;

import java.util.HashSet;
import java.util.Map;

/**
 * Performs a join between two plans that have imputations along their respective trees.
 * */
public class LogicalImputedJoinNode extends ImputedPlan {

    /** The first table to join (may be null). It's the alias of the table (if no alias, the true table name) */
    public String t1Alias;

    /** The second table to join (may be null).  It's the alias of the table, (if no alias, the true table name).*/
    public String t2Alias;

    /** The name of the field in t1 to join with. It's the pure name of a field, rather that alias.field. */
    public String f1PureName;

    public QuantifiedName f1QuantifiedName;

    /** The name of the field in t2 to join with. It's the pure name of a field.*/
    public String f2PureName;

    public QuantifiedName f2QuantifiedName;

    private TransactionId tid;

    // Plans with imputation
    private ImputedPlan table1;
    private ImputedPlan table2;

    // dirty set
    private HashSet<QuantifiedName> dirtySet;

    // physical plan
    private DbIterator physicalPlan;

    // need to able to lookup tableIds (usually this is in the LogicalPlan)
    private Map<String, Integer> tableMap;

    /** The join predicate */
    public Predicate.Op p;

    public LogicalImputedJoinNode(
        TransactionId tid,
        String table1Name,
        String table2Name,
        ImputedPlan table1,
        ImputedPlan table2,
        String joinField1,
        String joinField2,
        Predicate.Op pred,
        Map<String, Integer> tableMap) {
        this.tid = tid;
        t1Alias = table1Name;
        t2Alias = table2Name;
        this.table1 = table1;
        this.table2 = table2;
        String[] tmps = joinField1.split("[.]");
        if (tmps.length>1)
            f1PureName = tmps[tmps.length-1];
        else
            f1PureName=joinField1;
        tmps = joinField2.split("[.]");
        if (tmps.length>1)
            f2PureName = tmps[tmps.length-1];
        else
            f2PureName = joinField2;
        p = pred;
        this.f1QuantifiedName = new QuantifiedName(t1Alias, f1PureName);
        this.f2QuantifiedName = new QuantifiedName(t2Alias, f2PureName);

        // create physical plan
        int ixfield1 = table1.getPlan().getTupleDesc().fieldNameToIndex(f1QuantifiedName.toString());
        int ixfield2 = table2.getPlan().getTupleDesc().fieldNameToIndex(f2QuantifiedName.toString());
        JoinPredicate joinPred = new JoinPredicate(ixfield1, p, ixfield2);
        physicalPlan = new Join(joinPred, table1.getPlan(), table2.getPlan());

        // add dirty set info
        dirtySet = new HashSet<QuantifiedName>();
        // add dirty set of table 1
        dirtySet.addAll(table1.getDirtySet());
        // add dirty set of table 2
        dirtySet.addAll(table2.getDirtySet());

        if (dirtySet.contains(f1QuantifiedName) || dirtySet.contains(f2QuantifiedName)) {
            throw new IllegalArgumentException("Must impute all dirty attributes used by the join predicate");
        }
    }
    
    /** Return a new LogicalJoinNode with the inner and outer (t1.f1
     * and t2.f2) tables swapped. */
    public LogicalImputedJoinNode swapInnerOuter() {
        Predicate.Op newp;
        if (p == Predicate.Op.GREATER_THAN)
            newp = Predicate.Op.LESS_THAN;
        else if (p == Predicate.Op.GREATER_THAN_OR_EQ)
            newp = Predicate.Op.LESS_THAN_OR_EQ;
        else if (p == Predicate.Op.LESS_THAN)
            newp = Predicate.Op.GREATER_THAN;
        else if (p == Predicate.Op.LESS_THAN_OR_EQ)
            newp = Predicate.Op.GREATER_THAN_OR_EQ;
        else 
            newp = p;
        LogicalImputedJoinNode j2 = new LogicalImputedJoinNode(
            tid, t2Alias,t1Alias, table2, table1, f2PureName,f1PureName, newp, tableMap
        );
        return j2;
    }

    public double cardinality() {
        if (p.equals(Predicate.Op.EQUALS)) {
            if (isPkey(t1Alias, f1PureName)) {
                return table2.cardinality();
            } else if (isPkey(t2Alias, f2PureName)) {
                return table1.cardinality();
            } else {
                return Math.max(table1.cardinality(), table1.cardinality());
            }
        } else {
            return (int) (table1.cardinality() * table2.cardinality() * 0.3);
        }
    }

    // TODO: should this be the same as before? I don't think so....
    public double cost(double lossWeight) {
        return table1.cost(lossWeight) + table1.cardinality() * table2.cost(lossWeight) + table1.cardinality() * table2.cardinality();
    }


    private boolean isPkey(String tableAlias, String field) {
        // look up actual table name, which alias points to
        int tid1 = tableMap.get(tableAlias);
        String pkey1 = Database.getCatalog().getPrimaryKey(tid1);
        return pkey1.equals(field);
    }

    public DbIterator getPlan() {
        return physicalPlan;
    }

    public HashSet<QuantifiedName> getDirtySet() {
        return dirtySet;
    }

    @Override public boolean equals(Object o) {
        LogicalImputedJoinNode j2 =(LogicalImputedJoinNode)o;
        return (j2.t1Alias.equals(t1Alias)  || j2.t1Alias.equals(t2Alias)) && (j2.t2Alias.equals(t1Alias)  || j2.t2Alias.equals(t2Alias));
    }

    @Override public String toString() {
        return t1Alias + ":" + t2Alias ;//+ ";" + f1 + " " + p + " " + f2;
    }
    
    @Override public int hashCode() {
        return t1Alias.hashCode() + t2Alias.hashCode() + f1PureName.hashCode() + f2PureName.hashCode();
    }
}

