package me.zly2006.lvc.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;

final class LvcSshTransportFactory
{
    private static final List<String> DEFAULT_SSH_IDENTITY_NAMES = List.of("id_ed25519", "id_ecdsa", "id_rsa", "id_dsa");

    private LvcSshTransportFactory()
    {
    }

    static TransportConfigCallback transportConfigCallback()
    {
        return LvcSshTransportFactory::configureSshTransport;
    }

    static String sshIdentityDiagnostic()
    {
        Path homeDirectory = resolveSshHomeDirectory();
        Path sshDirectory = homeDirectory.resolve(".ssh");
        List<Path> identities = defaultSshIdentities(sshDirectory);

        if (identities.isEmpty())
        {
            return "LVC could not find an SSH private key. Java home=" + homeDirectory + "; expected one of " + DEFAULT_SSH_IDENTITY_NAMES + " in " + sshDirectory + ".";
        }

        return "LVC found SSH key file(s) " + identities + " but JGit could not use them. If the key has a passphrase, LVC needs passphrase prompt support; for the current MVP use an unencrypted OpenSSH key or configure a supported key file for github.com.";
    }

    private static void configureSshTransport(Transport transport)
    {
        if (transport instanceof SshTransport sshTransport)
        {
            Path homeDirectory = resolveSshHomeDirectory();
            Path sshDirectory = homeDirectory.resolve(".ssh");
            SshdSessionFactory sessionFactory = new SshdSessionFactoryBuilder()
                    .setHomeDirectory(homeDirectory.toFile())
                    .setSshDirectory(sshDirectory.toFile())
                    .setDefaultIdentities(sshDir -> defaultSshIdentities(sshDir.toPath()))
                    .build(null);
            sshTransport.setSshSessionFactory(sessionFactory);
        }
    }

    private static Path resolveSshHomeDirectory()
    {
        String envHome = System.getenv("HOME");

        if (envHome != null && !envHome.isBlank())
        {
            Path home = Path.of(envHome);

            if (hasDefaultSshIdentity(home))
            {
                return home;
            }
        }

        String propertyHome = System.getProperty("user.home");

        if (propertyHome != null && !propertyHome.isBlank())
        {
            return Path.of(propertyHome);
        }

        return Path.of(".");
    }

    private static boolean hasDefaultSshIdentity(Path homeDirectory)
    {
        Path sshDirectory = homeDirectory.resolve(".ssh");

        for (String identityName : DEFAULT_SSH_IDENTITY_NAMES)
        {
            if (Files.isRegularFile(sshDirectory.resolve(identityName)))
            {
                return true;
            }
        }

        return false;
    }

    private static List<Path> defaultSshIdentities(Path sshDirectory)
    {
        List<Path> identities = new ArrayList<>();

        for (String identityName : DEFAULT_SSH_IDENTITY_NAMES)
        {
            Path identity = sshDirectory.resolve(identityName);

            if (Files.isRegularFile(identity))
            {
                identities.add(identity);
            }
        }

        return List.copyOf(identities);
    }
}
