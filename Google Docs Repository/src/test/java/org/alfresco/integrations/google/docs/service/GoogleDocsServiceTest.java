package org.alfresco.integrations.google.docs.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.anyString;
import static org.powermock.api.mockito.PowerMockito.doNothing;
import static org.powermock.api.mockito.PowerMockito.doReturn;
import static org.powermock.api.mockito.PowerMockito.doThrow;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.when;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Date;
import java.util.Map;

import javax.annotation.Resource;

import org.alfresco.integrations.google.docs.exceptions.GoogleDocsAuthenticationException;
import org.alfresco.integrations.google.docs.exceptions.GoogleDocsRefreshTokenException;
import org.alfresco.integrations.google.docs.exceptions.GoogleDocsServiceException;
import org.alfresco.integrations.google.docs.exceptions.MustDowngradeFormatException;
import org.alfresco.integrations.google.docs.exceptions.MustUpgradeFormatException;
import org.alfresco.integrations.google.docs.utils.FileNameUtil;
import org.alfresco.repo.policy.BehaviourFilter;
import org.alfresco.repo.remotecredentials.OAuth2CredentialsInfoImpl;
import org.alfresco.repo.tenant.TenantService;
import org.alfresco.service.cmr.activities.ActivityService;
import org.alfresco.service.cmr.dictionary.DictionaryService;
import org.alfresco.service.cmr.lock.LockService;
import org.alfresco.service.cmr.model.FileFolderService;
import org.alfresco.service.cmr.oauth2.OAuth2CredentialsStoreService;
import org.alfresco.service.cmr.remotecredentials.OAuth2CredentialsInfo;
import org.alfresco.service.cmr.repository.MimetypeService;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.security.AuthorityService;
import org.alfresco.service.cmr.security.PersonService;
import org.alfresco.service.cmr.site.SiteService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.modules.junit4.PowerMockRunnerDelegate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;

/**
 * Created by Lucian Tuca on 17/08/16.
 */
@RunWith(PowerMockRunner.class) @PowerMockRunnerDelegate(SpringJUnit4ClassRunner.class) @ContextConfiguration("classpath:test-context.xml") @PrepareForTest({
    GoogleDocsServiceImpl.class, GoogleClientSecrets.class }) public class GoogleDocsServiceTest
{
    @Mock private OAuth2CredentialsStoreService oauth2CredentialsStoreService;
    @Mock private FileFolderService fileFolderService;
    @Mock private NodeService nodeService;
    @Mock private LockService lockservice;
    @Mock private MimetypeService mimetypeService;
    @Mock private BehaviourFilter behaviourFilter;
    @Mock private ActivityService activityService;
    @Mock private SiteService siteService;
    @Mock private TenantService tenantService;
    @Mock private PersonService personService;
    @Mock private AuthorityService authorityService;
    @Mock private DictionaryService dictionaryService;
    @Mock private FileNameUtil filenameUtil;

    @Resource(name = "importFormats") private Map<String, String> importFormats;
    @Resource(name = "exportFormats") private Map<String, Map<String, String>> exportFormats;
    @Resource(name = "upgradeMappings") private Map<String, String> upgradeMappings;

    @Mock private HttpTransport httpTransport = new NetHttpTransport();
    @Mock private JacksonFactory jsonFactory = new JacksonFactory();
    @Mock private GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(jsonFactory,
        new InputStreamReader(this.getClass().getResourceAsStream("/client_secret.json")));

    @Spy @InjectMocks private GoogleDocsServiceImpl googleDocsService = new GoogleDocsServiceImpl();

    public GoogleDocsServiceTest() throws IOException
    {

    }

    @Before public void setup()
    {
        // Manually inject these fields into GoogleDocsService
        googleDocsService.setImportFormats(importFormats);
        googleDocsService.setExportFormats(exportFormats);
        googleDocsService.setUpgradeMappings(upgradeMappings);
    }

    @Test public void testGetCredentialBadFlow()
        throws GoogleDocsAuthenticationException, GoogleDocsServiceException, IOException,
        GoogleDocsRefreshTokenException
    {
        when(oauth2CredentialsStoreService.getPersonalOAuth2Credentials(anyString()))
            .thenReturn(null);
        Credential credential = googleDocsService.getCredential();
        assertNull(credential);
    }

    @Test public void testGetCredentialHappyFlow() throws Exception
    {
        OAuth2CredentialsInfo credentialsInfo = mock(OAuth2CredentialsInfoImpl.class);

        when(oauth2CredentialsStoreService.getPersonalOAuth2Credentials(anyString()))
            .thenReturn(credentialsInfo);
        when(credentialsInfo.getOAuthAccessToken()).thenReturn("access_token");
        when(credentialsInfo.getOAuthRefreshToken()).thenReturn("refresh_token");
        when(credentialsInfo.getOAuthTicketExpiresAt())
            .thenReturn(new Date(1597670820000L)); // 8/17/20 1:27 PM
        doNothing().when(googleDocsService, "testConnection", anyObject());

        Credential retrievedCredential = googleDocsService.getCredential();
        assertNotNull(retrievedCredential);
    }

    @Test(expected = GoogleDocsServiceException.class) public void testGetCredentialTestConnectionThrowsGoogleDocsServiceException()
        throws Exception
    {
        OAuth2CredentialsInfo credentialsInfo = mock(OAuth2CredentialsInfoImpl.class);

        when(oauth2CredentialsStoreService.getPersonalOAuth2Credentials(anyString()))
            .thenReturn(credentialsInfo);

        when(credentialsInfo.getOAuthAccessToken()).thenReturn("access_token");
        when(credentialsInfo.getOAuthRefreshToken()).thenReturn("refresh_token");
        when(credentialsInfo.getOAuthTicketExpiresAt())
            .thenReturn(new Date(1597670820000L)); // 8/17/20 1:27 PM
        doThrow(new GoogleDocsServiceException("GoogleDocsServiceException"))
            .when(googleDocsService, "testConnection", anyObject());

        Credential retrievedCredential = googleDocsService.getCredential();
        assertNotNull(retrievedCredential);
    }

    @Test public void testIsAuthenticatedBadFlowNullCredentialInfo()
    {
        when(oauth2CredentialsStoreService.getPersonalOAuth2Credentials(anyString()))
            .thenReturn(null);
        boolean authenticated = googleDocsService.isAuthenticated();
        assertFalse(authenticated);
    }

    @Test(expected = MustUpgradeFormatException.class) public void testIsExportableMustUpgradeFlow()
        throws Exception
    {
        doReturn(true).when(googleDocsService, "isUpgrade", anyString());
        googleDocsService.isExportable(anyString());
    }

    @Test(expected = MustDowngradeFormatException.class) public void testIsExportableMustDowngradeFlow()
        throws Exception
    {
        doReturn(false).when(googleDocsService, "isUpgrade", anyString());
        doReturn(true).when(googleDocsService, "isDownGrade", anyString());
        googleDocsService.isExportable(anyString());
    }

    @Test public void testIsExportableHappyFlow() throws Exception
    {
        doReturn(false).when(googleDocsService, "isUpgrade", anyString());
        doReturn(false).when(googleDocsService, "isDownGrade", anyString());

        // This one is failing. Should this be exportable or not?
        googleDocsService.isExportable("text/rtf");

        // Documents
        Map<String, String> documentExportableTypes = exportFormats.get("document");
        for (String key : documentExportableTypes.keySet())
        {
            assertTrue(googleDocsService.isExportable(key));
        }

        // Spreadsheets
        Map<String, String> spreadsheetExportableTypes = exportFormats.get("spreadsheet");
        for (String key : spreadsheetExportableTypes.keySet())
        {
            assertTrue(googleDocsService.isExportable(key));
        }

        // Presentations
        Map<String, String> presentationExportableTypes = exportFormats.get("presentation");
        for (String key : presentationExportableTypes.keySet())
        {
            assertTrue(googleDocsService.isExportable(key));
        }
    }

    @Test public void testIsImportableBadFlow()
    {
        assertFalse(googleDocsService.isImportable("application/json"));
    }

    @Test public void testIsImportableHappyFlow()
    {
        for (String key : importFormats.keySet()) {
            assertTrue(googleDocsService.isImportable(key));
        }
    }

}
