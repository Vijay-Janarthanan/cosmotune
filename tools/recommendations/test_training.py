import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("train", Path(__file__).with_name("train.py"))
train = importlib.util.module_from_spec(spec)
spec.loader.exec_module(train)


class TrainingTest(unittest.TestCase):
    def test_unicode_identity(self):
        self.assertNotEqual(train.norm("కల"), train.norm("కాల"))
        self.assertNotEqual(train.norm("கலை"), train.norm("கலி"))
        self.assertEqual("𠀀", train.norm("𠀀"))
        self.assertEqual("song", train.title_key('Song (From "Film")'))
        self.assertEqual("song live", train.title_key("Song (Live)"))

    def test_graph_excludes_self_and_single_listener_edges(self):
        matrix = train.csr_profiles([[0, 1], [0, 1], [0, 2]], 3)
        graph, degree = train.listening_graph(matrix)
        self.assertEqual([1], [i for i, _, _ in graph[0]])
        self.assertEqual([0], [i for i, _, _ in graph[1]])
        self.assertFalse(graph[2])
        self.assertEqual([3, 2, 1], degree.tolist())


if __name__ == "__main__":
    unittest.main()
