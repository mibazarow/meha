package com.andharness.ru.data.ssh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the destructive-command heuristic of update.md §5.4 (pure JVM, no Android, no JSch).
 *
 * Two directions matter and both are asserted: every form listed in the ТЗ must be caught
 * (a false negative would let a destructive command run silently), and ordinary commands must NOT be
 * flagged (a false positive would nag the user for `ls`).
 */
class DestructiveCommandPolicyTest {

    private fun flagged(command: String): Boolean = DestructiveCommandPolicy.isDestructive(command)

    // ---- flagged (update.md §5.4 list + obvious siblings) ----

    @Test
    fun recursiveAndForcedRemovalIsFlagged() {
        assertTrue(flagged("rm -rf /"))
        assertTrue(flagged("rm -rf /var/www/*"))
        assertTrue(flagged("rm -r /tmp/foo"))
        assertTrue(flagged("rm -R -f /opt/data"))
        assertTrue(flagged("rm --recursive /srv"))
        assertTrue(flagged("rm -fr ~/projects"))
        assertTrue(flagged("rm -vrf /var/log"))
    }

    @Test
    fun sudoAndShellWrappersDoNotHideTheCommand() {
        assertTrue(flagged("sudo rm -rf /var/lib/data"))
        assertTrue(flagged("sudo -u root rm -rf /data"))
        assertTrue(flagged("doas rm -rf /data"))
        assertTrue(flagged("sh -c 'rm -rf /tmp/x'"))
        assertTrue(flagged("bash -lc \"rm -rf /tmp/x\""))
        assertTrue(flagged("DATA=/tmp rm -rf \$DATA"))
    }

    @Test
    fun compoundCommandsAreInspectedSegmentBySegment() {
        assertTrue(flagged("echo ok && rm -rf /tmp/x"))
        assertTrue(flagged("true; dd if=/dev/zero of=/dev/sda"))
        assertTrue(flagged("ls | xargs rm -r"))
    }

    @Test
    fun diskLevelToolsAreFlagged() {
        assertTrue(flagged("dd if=/dev/zero of=/dev/sda bs=1M"))
        assertTrue(flagged("mkfs.ext4 /dev/sda1"))
        assertTrue(flagged("mkfs -t xfs /dev/nvme0n1p2"))
        assertTrue(flagged("wipefs -a /dev/sda"))
        assertTrue(flagged("mkswap /dev/sdb1"))
        assertTrue(flagged("fdisk /dev/sda"))
        assertTrue(flagged("parted /dev/sda mklabel gpt"))
        assertTrue(flagged("shred -u secret.key"))
        assertTrue(flagged("mdadm --zero-superblock /dev/sda1"))
    }

    @Test
    fun powerAndServiceControlIsFlagged() {
        assertTrue(flagged("reboot"))
        assertTrue(flagged("reboot -f"))
        assertTrue(flagged("shutdown -h now"))
        assertTrue(flagged("sudo shutdown now"))
        assertTrue(flagged("halt"))
        assertTrue(flagged("systemctl stop nginx"))
        assertTrue(flagged("sudo systemctl disable sshd"))
        assertTrue(flagged("systemctl mask firewalld"))
        assertTrue(flagged("systemctl --user stop myservice"))
    }

    @Test
    fun recursivePermissionChangesAreFlagged() {
        assertTrue(flagged("chmod -R 777 /var/www"))
        assertTrue(flagged("chmod --recursive 755 /opt"))
        assertTrue(flagged("chown -R www-data:www-data /srv"))
        assertTrue(flagged("chgrp -R devs /opt"))
    }

    @Test
    fun deviceRedirectionAndFirewallFlushAreFlagged() {
        assertTrue(flagged("echo wipe > /dev/sda"))
        assertTrue(flagged("cat image.iso > /dev/nvme0n1"))
        assertTrue(flagged("> /dev/mmcblk0"))
        assertTrue(flagged("iptables -F"))
        assertTrue(flagged("sudo iptables --flush"))
        assertTrue(flagged("ip6tables -F"))
    }

    @Test
    fun forkBombIsFlagged() {
        assertTrue(flagged(":(){ :|:& };:"))
    }

    @Test
    fun everyFlaggedCommandCarriesAReason() {
        val verdict = DestructiveCommandPolicy.inspect("sudo rm -rf /var/www")
        assertTrue(verdict.destructive)
        assertTrue("reasons must be non-empty", verdict.reasons.isNotEmpty())
        assertTrue(
            "the reason should mention the rule, was: ${verdict.reasonText}",
            verdict.reasonText.contains("rm")
        )
    }

    // ---- not flagged ----

    @Test
    fun ordinaryAdministrationIsNotFlagged() {
        assertFalse(flagged("ls -la /var/www"))
        assertFalse(flagged("cat /var/log/syslog"))
        assertFalse(flagged("tail -n 200 /var/log/nginx/access.log"))
        assertFalse(flagged("systemctl status nginx"))
        assertFalse(flagged("systemctl restart nginx"))
        assertFalse(flagged("df -h"))
        assertFalse(flagged("free -m"))
        assertFalse(flagged("chmod 644 /var/www/index.html"))
        assertFalse(flagged("chown www-data /srv/app"))
        assertFalse(flagged("rm /tmp/one-file.txt"))
        assertFalse(flagged("rm -f /tmp/one-file.txt"))
        assertFalse(flagged("grep -r TODO /opt/src"))
        assertFalse(flagged("docker ps -a"))
    }

    @Test
    fun wordsThatMerelyContainDestructiveFragmentsAreNotFlagged() {
        assertFalse(flagged("echo 'rm -rf /'"))
        assertFalse(flagged("address --all"))
        assertFalse(flagged("git add ."))
        assertFalse(flagged("find / -name '*.log'"))
        assertFalse(flagged("printf 'reboot is a word'"))
    }

    @Test
    fun blankCommandsAreNotFlagged() {
        assertFalse(DestructiveCommandPolicy.isDestructive(null))
        assertFalse(DestructiveCommandPolicy.isDestructive(""))
        assertFalse(DestructiveCommandPolicy.isDestructive("   "))
    }
}
