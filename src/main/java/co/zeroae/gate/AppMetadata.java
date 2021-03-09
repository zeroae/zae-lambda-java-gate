package co.zeroae.gate;
/**
 * This class structure must match https://github.com/GateNLP/cloud-client/blob/master/library/src/main/java/uk/ac/gate/cloud/online/ServiceMetadata.java
 */
class AppMetadata {
    public String name;

    public String defaultAnnotations;

    public String additionalAnnotations;

    public int costPerRequest;

    public int dailyQuota;
}
