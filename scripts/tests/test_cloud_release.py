import io
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from cloud_release import unpack


class CloudApplicationArchiveTests(unittest.TestCase):
    def archive(self,folder,name,kind=tarfile.REGTYPE):
        path=folder/'apps.tar.gz'
        with tarfile.open(path,'w:gz') as stream:
            member=tarfile.TarInfo(name);member.type=kind
            if kind==tarfile.SYMTYPE:member.linkname='/etc/passwd'
            else:member.size=3
            stream.addfile(member,io.BytesIO(b'jar') if kind==tarfile.REGTYPE else None)
        return path

    def test_only_expected_application_file_is_restored(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);archive=self.archive(root,'backend.jar');target=root/'restore'
            unpack(archive,target,{'backend.jar'})
            self.assertEqual((target/'backend.jar').read_bytes(),b'jar')
            with self.assertRaises(RuntimeError):unpack(archive,target,{'backend.jar'})

    def test_parent_paths_and_links_are_rejected(self):
        for name,kind in [('../outside',tarfile.REGTYPE),('/outside',tarfile.REGTYPE),('backend.jar',tarfile.SYMTYPE)]:
            with self.subTest(name=name,kind=kind),tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp)
                with self.assertRaises(RuntimeError):unpack(self.archive(root,name,kind),root/'restore')
                self.assertFalse((root/'outside').exists())

    def test_unexpected_package_members_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            with self.assertRaises(RuntimeError):unpack(self.archive(root,'other.jar'),root/'restore',{'backend.jar'})


if __name__=='__main__':unittest.main()
