from hfm_mcp.compat import version_warning


def test_matching_and_dev_builds_do_not_warn():
    assert version_warning("0.2.3", "0.2.3") is None
    assert version_warning("0.0.0-dev", "0.2.1") is None  # development client
    assert version_warning("0.2.3", "0.0.0-dev") is None  # development daemon


def test_older_daemon_says_update_the_server():
    w = version_warning("0.2.3", "0.2.1")
    assert w is not None and "install.ps1 on the HFM server" in w and "0.2.1" in w
    w = version_warning("0.2.3", None)  # before daemons reported a version
    assert w is not None and "install.ps1" in w


def test_older_client_says_update_this_pc():
    w = version_warning("0.2.3", "0.10.0")  # numeric, not text, comparison
    assert w is not None and "install-client.ps1" in w
