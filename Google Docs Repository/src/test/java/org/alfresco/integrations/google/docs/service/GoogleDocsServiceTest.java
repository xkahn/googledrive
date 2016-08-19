package org.alfresco.integrations.google.docs.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.when;
import static org.powermock.api.mockito.PowerMockito.whenNew;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Resource;

import org.alfresco.integrations.google.docs.exceptions.GoogleDocsAuthenticationException;
import org.alfresco.integrations.google.docs.exceptions.GoogleDocsRefreshTokenException;
import org.alfresco.integrations.google.docs.exceptions.GoogleDocsServiceException;
import org.alfresco.integrations.google.docs.exceptions.MustDowngradeFormatException;
import org.alfresco.integrations.google.docs.exceptions.MustUpgradeFormatException;
import org.alfresco.integrations.google.docs.utils.FileNameUtil;
import org.alfresco.repo.model.filefolder.FileInfoImpl;
import org.alfresco.repo.policy.BehaviourFilter;
import org.alfresco.repo.remotecredentials.OAuth2CredentialsInfoImpl;
import org.alfresco.repo.tenant.TenantService;
import org.alfresco.service.cmr.activities.ActivityService;
import org.alfresco.service.cmr.dictionary.DictionaryService;
import org.alfresco.service.cmr.lock.LockService;
import org.alfresco.service.cmr.model.FileFolderService;
import org.alfresco.service.cmr.oauth2.OAuth2CredentialsStoreService;
import org.alfresco.service.cmr.remotecredentials.OAuth2CredentialsInfo;
import org.alfresco.service.cmr.repository.ContentData;
import org.alfresco.service.cmr.repository.MimetypeService;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.security.AuthorityService;
import org.alfresco.service.cmr.security.PersonService;
import org.alfresco.service.cmr.site.SiteService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.modules.junit4.PowerMockRunnerDelegate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import com.google.api.client.auth.oauth2.ClientParametersAuthentication;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.oauth2.Oauth2;
import com.google.api.services.oauth2.model.Userinfoplus;

/**
 * Created by Lucian Tuca on 17/08/16.
 */
@RunWith(PowerMockRunner.class)
@PowerMockRunnerDelegate(SpringJUnit4ClassRunner.class)
@ContextConfiguration("classpath:test-context.xml") @PrepareForTest({ GoogleDocsServiceImpl.class,
    GoogleClientSecrets.class, GoogleJsonResponseException.class, Oauth2.Builder.class,
    Credential.class, Oauth2.Userinfo.class, Oauth2.Userinfo.Get.class, Userinfoplus.class,
    NodeRef.class })
public class GoogleDocsServiceTest
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
    @Resource(name = "downgradeMappings") private Map<String, String> downgradeMappings;

    @Mock private HttpTransport httpTransport = new NetHttpTransport();
    @Mock private JacksonFactory jsonFactory = new JacksonFactory();
    @Mock private GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(jsonFactory,
        new InputStreamReader(this.getClass().getResourceAsStream("/client_secret.json")));

    @InjectMocks private GoogleDocsServiceImpl googleDocsService = new GoogleDocsServiceImpl();

    public GoogleDocsServiceTest() throws IOException
    {

    }

    /**
     * Manually inject the spring beans into the GoogleDocsService
     */
    @Before public void setup()
    {
        googleDocsService.setImportFormats(importFormats);
        googleDocsService.setExportFormats(exportFormats);
        googleDocsService.setUpgradeMappings(upgradeMappings);
        googleDocsService.setDowngradeMappings(downgradeMappings);
    }

    @Test public void testIsImportableBadFlow()
    {
        assertFalse(googleDocsService.isImportable("application/json"));
    }

    @Test public void testIsImportableHappyFlow()
    {
        for (String key : importFormats.keySet())
        {
            assertTrue(googleDocsService.isImportable(key));
        }
    }

    /**
     * Verifies that for all upgrade mappings isExportable throws MustUpgradeFormatException.
     *
     * @throws Exception
     */
    @Test public void testIsExportableMustUpgradeFlow() throws Exception
    {
        int numberOfUpgradeMappings = upgradeMappings.keySet().size();
        int actualUpgradeMappingsDetected = 0;

        for (String mapping : upgradeMappings.keySet())
        {
            try
            {
                googleDocsService.isExportable(mapping);
            }
            catch (MustUpgradeFormatException mufe)
            {
                actualUpgradeMappingsDetected++;
            }
        }

        assertEquals(numberOfUpgradeMappings, actualUpgradeMappingsDetected);
    }

    /**
     * Verifies isExportable throws MustDowngradeMapping when such mapping is provided.
     *
     * @throws Exception
     */
    @Test(expected = MustDowngradeFormatException.class) public void testIsExportableMustDowngradeFlow()
        throws Exception
    {
        googleDocsService.isExportable("downgrade/mapping");
    }

    @Test public void testIsExportableHappyFlow() throws Exception
    {
        // Documents
        Set<String> documentExportableTypes = exportFormats.get("document").keySet();
        // Spreadsheets
        Set<String> spreadsheetExportableTypes = exportFormats.get("spreadsheet").keySet();
        // Presentations
        Set<String> presentationExportableTypes = exportFormats.get("presentation").keySet();

        // Must upgrade types
        Set<String> mustUpgradeTypes = upgradeMappings.keySet();

        Set<String> allExportableTypes = new HashSet<>();
        allExportableTypes.addAll(documentExportableTypes);
        allExportableTypes.addAll(spreadsheetExportableTypes);
        allExportableTypes.addAll(presentationExportableTypes);
        allExportableTypes.removeAll(mustUpgradeTypes);

        for (String mimetype : allExportableTypes)
        {
            assertTrue(googleDocsService.isExportable(mimetype));
        }
    }

    @Test public void testGetContentTypeHappyFlow()
    {
        NodeRef nodeRef = mock(NodeRef.class);
        FileInfoImpl fileInfo = mock(FileInfoImpl.class);
        ContentData contentData = mock(ContentData.class);

        when(fileFolderService.getFileInfo(nodeRef)).thenReturn(fileInfo);
        when(fileInfo.getContentData()).thenReturn(contentData);
        when(contentData.getMimetype()).thenReturn("text/csv");
        String contentType = googleDocsService.getContentType(nodeRef);
        assertEquals("spreadsheet", contentType);
    }

    @Test public void testGetContentTypeBadFlow()
    {
        NodeRef nodeRef = mock(NodeRef.class);
        FileInfoImpl fileInfo = mock(FileInfoImpl.class);
        ContentData contentData = mock(ContentData.class);

        when(fileFolderService.getFileInfo(nodeRef)).thenReturn(fileInfo);
        when(fileInfo.getContentData()).thenReturn(contentData);
        when(contentData.getMimetype()).thenReturn("I/DON'T/EXIST");
        String contentType = googleDocsService.getContentType(nodeRef);
        assertNull(contentType);
    }

    /**
     * Verify that a null credential is returned when null credentialsInfo are provided.
     * Very less likely to happen.
     *
     * @throws GoogleDocsAuthenticationException
     * @throws GoogleDocsServiceException
     * @throws IOException
     * @throws GoogleDocsRefreshTokenException
     */
    @Test public void testGetCredentialBadFlow()
        throws GoogleDocsAuthenticationException, GoogleDocsServiceException, IOException,
        GoogleDocsRefreshTokenException
    {
        when(oauth2CredentialsStoreService.getPersonalOAuth2Credentials(anyString()))
            .thenReturn(null);
        Credential credential = googleDocsService.getCredential();
        assertNull(credential);
    }

    /**
     * Verifys the correct flow when "valid" data is obtained.
     *
     * @throws Exception
     */
    @Test public void testGetCredentialHappyFlow() throws Exception
    {
        OAuth2CredentialsInfo credentialsInfo = mock(OAuth2CredentialsInfoImpl.class);
        Credential.Builder credentialBuilder = mock(Credential.Builder.class);
        Credential credential = mock(Credential.class);

        // Mock OAuth part
        Oauth2.Builder oauth2Builder = mock(Oauth2.Builder.class);
        Oauth2.Userinfo.Get oauth2UserInfoGet = mock(Oauth2.Userinfo.Get.class);
        Oauth2 userInfoService = mock(Oauth2.class);
        Oauth2.Userinfo oauth2UserInfo = mock(Oauth2.Userinfo.class);
        Userinfoplus userinfoplus = mock(Userinfoplus.class);

        // Mock the credentialsInfo
        when(oauth2CredentialsStoreService.getPersonalOAuth2Credentials(anyString()))
            .thenReturn(credentialsInfo);
        when(credentialsInfo.getOAuthAccessToken()).thenReturn("access_token");
        when(credentialsInfo.getOAuthRefreshToken()).thenReturn("refresh_token");
        when(credentialsInfo.getOAuthTicketExpiresAt())
            .thenReturn(new Date(1597670820000L)); // 8/17/20 1:27 PM

        // Mock credential obtaining
        ClientParametersAuthentication clientParametersAuthentication = new ClientParametersAuthentication(
            clientSecrets.getDetails().getClientId(), clientSecrets.getDetails().getClientSecret());
        String tokenUri = clientSecrets.getDetails().getTokenUri();

        whenNew(Credential.Builder.class).withAnyArguments().thenReturn(credentialBuilder);
        when(credentialBuilder.setJsonFactory(jsonFactory)).thenReturn(credentialBuilder);
        when(credentialBuilder.setTransport(httpTransport)).thenReturn(credentialBuilder);
        when(credentialBuilder.setClientAuthentication(clientParametersAuthentication))
            .thenReturn(credentialBuilder);
        when(credentialBuilder.setTokenServerEncodedUrl(tokenUri)).thenReturn(credentialBuilder);
        when(credentialBuilder.build()).thenReturn(credential);
        when(credential.setAccessToken(anyString())).thenReturn(credential);
        when(credential.setRefreshToken(anyString())).thenReturn(credential);
        when(credential.setExpirationTimeMilliseconds(anyLong())).thenReturn(credential);

        // Mock testConnection
        whenNew(Oauth2.Builder.class).withAnyArguments().thenReturn(oauth2Builder);
        when(oauth2Builder.setApplicationName(anyString())).thenReturn(oauth2Builder);
        when(oauth2Builder.build()).thenReturn(userInfoService);
        when(userInfoService.userinfo()).thenReturn(oauth2UserInfo);
        when(oauth2UserInfo.get()).thenReturn(oauth2UserInfoGet);
        when(oauth2UserInfoGet.execute()).thenReturn(userinfoplus);
        when(oauth2UserInfoGet.setFields(anyString())).thenReturn(oauth2UserInfoGet);
        when(userinfoplus.getId()).thenReturn("someId");

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

    @Test public void testIsAuthenticatedBadFlowGetCredentialThrowsException() throws Exception
    {
        OAuth2CredentialsInfo credentialsInfo = mock(OAuth2CredentialsInfoImpl.class);
        Credential credential = mock(Credential.class);

        when(oauth2CredentialsStoreService.getPersonalOAuth2Credentials(anyString()))
            .thenReturn(credentialsInfo);
        whenNew(Credential.Builder.class).withAnyArguments().thenThrow(new RuntimeException());

        boolean authenticated = googleDocsService.isAuthenticated();
        assertFalse(authenticated);
    }
}
