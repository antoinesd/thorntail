package org.wildfly.swarm.jaxrs;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wildfly.swarm.jose.Jose;
import org.wildfly.swarm.jose.JoseLookup;
/**
 *
 */
@RunWith(Arquillian.class)
public class JoseTest {

    @Deployment
    public static Archive<?> createDeployment() throws Exception {
        JAXRSArchive deployment = ShrinkWrap.create(JAXRSArchive.class);
        deployment.addResource(MyResource.class);
        deployment.addResource(SecuredApplication.class);
        deployment.addAllDependencies();
        deployment.addAsResource("keystore.jks");
        deployment.addAsResource("project-defaults.yml");
        return deployment;
    }

    @Test
    public void testJwsCompact() throws Exception {
        Client client = ClientBuilder.newClient();
 
        Jose jose = getJose();
        String signedData =
                client.target("http://localhost:8080")
                      .path("sign")
                      .request(MediaType.TEXT_PLAIN)
                      .post(Entity.entity(jose.sign("Hello"), "text/plain"), String.class);
        Assert.assertEquals("Hello", jose.verify(signedData));
    }
    
    @Test
    public void testJweCompact() throws Exception {
        Client client = ClientBuilder.newClient();
 
        try {
            Jose jose = getJose();
            String signedData =
                    client.target("http://localhost:8080")
                          .path("encrypt")
                          .request(MediaType.TEXT_PLAIN)
                          .post(Entity.entity(jose.encrypt("Hello"), "text/plain"), String.class);
            Assert.assertEquals("Hello", jose.decrypt(signedData));
        } catch (Exception ex) {
            throw new RuntimeException(ex.getMessage());
        }
    }
    
    private Jose getJose() throws Exception {
        return JoseLookup.lookup().get();
    }
}
