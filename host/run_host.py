"""PyInstaller entry (a package __main__ can't be frozen directly)."""
import sys
from thumbracer.app import main

sys.exit(main())
