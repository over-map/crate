"""Forward migrations for data/ files.

Every data file has a ``schema`` number. When a file format changes, add ``v<N>_to_v<N+1>.py`` here with a
pure ``migrate(data) -> data`` function, bump ``library.SCHEMA``, and run it once from a one-off script or
``crate`` command. The app refuses files with a newer schema than it knows ("update the app").
There are no migrations yet: every file is schema 1.
"""
