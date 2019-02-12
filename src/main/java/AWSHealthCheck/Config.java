package AWSHealthCheck;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public class Config {
    private List<String> regions;
    private List<String> category;
    private List<String> status;
    private Collection<Map<String, String>> tags;
    private String ses_region;
    private String ses_from;
    private String ses_send;
    private String email_template;

    public List<String> getRegions() {
        return regions;
    }

    public void setRegions(List<String> regions) {
        this.regions = regions;
    }

    public String getSes_region() {
        return ses_region;
    }

    public void setSes_region(String ses_region) {
        this.ses_region = ses_region;
    }

    public String getSes_from() {
        return ses_from;
    }

    public void setSes_from(String ses_from) {
        this.ses_from = ses_from;
    }

    public String getSes_send() {
        return ses_send;
    }

    public void setSes_send(String ses_send) {
        this.ses_send = ses_send;
    }

    public String getEmail_template() {
        return email_template;
    }

    public void setEmail_template(String email_template) {
        this.email_template = email_template;
    }

    public List<String> getCategory() {
        return category;
    }

    public void setCategory(List<String> category) {
        this.category = category;
    }

    public List<String> getStatus() {
        return status;
    }

    public void setStatus(List<String> status) {
        this.status = status;
    }

    public Collection<Map<String, String>> getTags() {
        return tags;
    }

    public void setTags(Collection<Map<String, String>> tags) {
        this.tags = tags;
    }
}
